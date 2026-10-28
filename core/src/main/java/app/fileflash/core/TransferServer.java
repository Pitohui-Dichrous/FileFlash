package app.fileflash.core;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

import static app.fileflash.core.HttpServer.HttpError;

/** Android-independent, bounded-memory LAN transfer engine. All timestamps use epoch milliseconds. */
public final class TransferServer implements AutoCloseable {
    public static final int CHUNK_SIZE = 2 * 1024 * 1024;
    private static final int JSON_LIMIT = 1024 * 1024;
    private static final long TICKET_LIFETIME = 10 * 60_000L;
    private static final long SESSION_LIFETIME = 24 * 60 * 60_000L;
    private static final long IDLE_BUDGET_GRACE = 30 * 60_000L;
    private static final int RECEIPT_LIMIT = 256;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setStrictness(Strictness.STRICT).create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final File root, incoming, received;
    private final AssetProvider assets;
    private final HttpServer http;
    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ShareFile> files = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Upload> uploads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Download> downloads = new ConcurrentHashMap<>();
    // Keep a worker available for state, cancellation and pairing during large transfers.
    private final Semaphore downloadSlots=new Semaphore(3);
    private final Semaphore uploadSlots=new Semaphore(2);
    private final Deque<TextMessage> texts = new ArrayDeque<>();
    private final Map<String, Rate> pairingRates = new LinkedHashMap<>();
    private volatile String pairToken = randomToken(24);
    private volatile boolean running;

    public TransferServer(File storageDir, AssetProvider assets) throws IOException {
        this.root = storageDir.getCanonicalFile();
        this.incoming = new File(root, "uploads");
        this.received = new File(root, "received");
        this.assets = Objects.requireNonNull(assets);
        mkdir(incoming); mkdir(received);
        if(!incoming.getCanonicalFile().getParentFile().equals(root)||!received.getCanonicalFile().getParentFile().equals(root))
            throw new IOException("接收缓存目录必须位于应用存储目录内");
        recover();
        this.http = new HttpServer(this::handle);
    }

    public synchronized void start(int port) throws IOException {
        if (running) return;
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        http.start(port);
        running = true;
    }

    public synchronized void stop() {
        running = false;
        sessions.clear(); tickets.clear();
        pairToken = randomToken(24);
        http.stop();
        for(Upload upload:uploads.values())if(upload.restartOnly&&!isComplete(upload)) {
            requestCancel(upload);
            if(upload.activeRequests==0)synchronized(upload) {try {discardRestartOnly(upload);}catch(IOException ignored){ }}
        }
        for (Download d : downloads.values()) synchronized (d) {
            if (d.status.equals("downloading")) { d.status="failed"; d.error="服务已停止"; }
        }
    }

    @Override public void close() { stop(); }
    public int getPort() { return http.port(); }
    public String getPairToken() { return pairToken; }
    public boolean isRunning() { return running; }

    public ShareFile share(String name, long size, String mime, Source source) {
        if (size < 0) throw new IllegalArgumentException("File size must be known");
        if (files.size() >= 512) throw new IllegalStateException("共享文件过多，请移除部分文件");
        ShareFile file = new ShareFile();
        file.id=randomToken(18); file.name=safeName(name); file.size=size; file.mime=safeMime(mime);
        file.createdAt=System.currentTimeMillis(); file.source=Objects.requireNonNull(source);
        files.put(file.id,file);
        return file;
    }

    public ShareFile share(String name,long size,String mime,String sha256,Source source) {
        if(!isSha(sha256)) throw new IllegalArgumentException("SHA-256 must be 64 hexadecimal characters");
        ShareFile file=share(name,size,mime,source);file.sha256=sha256.toLowerCase(Locale.ROOT);return file;
    }

    /** Restores an already saved, user-owned download without copying it into private storage. */
    public ShareFile restoreReceivedShare(String id,String name,long size,String mime,String sha256,long createdAt,Source source) {
        if(!validId(id)||size<0||(sha256!=null&&!isSha(sha256)))throw new IllegalArgumentException("Invalid saved file metadata");
        ShareFile file=new ShareFile();file.id=id;file.name=safeName(name);file.size=size;file.mime=safeMime(mime);
        file.sha256=sha256==null?null:sha256.toLowerCase(Locale.ROOT);file.createdAt=createdAt;file.received=true;
        file.source=Objects.requireNonNull(source);files.put(id,file);return file;
    }

    /** Convenience for desktop tests and locally received files; reopening uses a long seek. */
    public ShareFile share(File source) throws IOException {
        File canonical=source.getCanonicalFile();
        if (!canonical.isFile()) throw new FileNotFoundException("文件不存在");
        return share(canonical.getName(),canonical.length(),"application/octet-stream", offset -> openFile(canonical,offset));
    }

    /** Removes a share from future downloads. Received data stays on disk until explicitly deleted by the host. */
    public void removeFile(String id) { files.remove(id); tickets.entrySet().removeIf(e -> e.getValue().fileId.equals(id)); }

    public File receivedFile(String id) {
        Upload upload=uploads.get(id);
        if (upload == null) return null;
        synchronized (upload) {
            if (!"complete".equals(upload.status) || upload.finalName == null) return null;
            File file=new File(received,upload.finalName);
            return file.isFile() ? file : null;
        }
    }

    /**
     * Pins the private source while the host explicitly saves it. Save intent is durable before
     * returning the file: a failed export remains protected for retry, including after restart.
     * Close the claim in a finally block; only releaseReceivedCache may discard its verified copy.
     */
    public ReceivedSave claimReceivedSave(String id) throws IOException {
        Upload upload=uploads.get(id);
        if(upload==null)throw new IOException("临时文件已清理，请重新上传");
        synchronized(upload.lifecycle) {
            if(upload.maintenance||uploads.get(id)!=upload)throw new IOException("临时文件正在清理，请重新上传");
            upload.activeRequests++;
        }
        boolean claimed=false;
        try {
            synchronized(upload) {
                if(!"complete".equals(upload.status)||upload.finalName==null)throw new IOException("文件尚未接收完成，或已经保存");
                File source=ownedFile(received,upload.finalName);
                if(!source.isFile()||source.length()!=upload.size)throw new IOException("临时文件不可读取，请重新上传");
                if(!upload.saveRequested) {
                    upload.saveRequested=true;
                    try {persist(upload);}catch(IOException failure){upload.saveRequested=false;throw failure;}
                    ShareFile file=files.get(id);if(file!=null)file.temporary=false;
                }
                claimed=true;return new ReceivedSave(upload,source);
            }
        } finally {if(!claimed)synchronized(upload.lifecycle){upload.activeRequests--;}}
    }

    public static final class ReceivedSave implements Closeable {
        private Upload upload;
        private final File source;
        private ReceivedSave(Upload upload,File source){this.upload=upload;this.source=source;}
        public File file(){return source;}
        @Override public synchronized void close(){if(upload!=null){synchronized(upload.lifecycle){upload.activeRequests--;}upload=null;}}
    }

    /** Deletes only this engine's private received copy. SAF sources and exported copies are untouched. */
    public void removeReceivedFile(String id) throws IOException {
        Upload upload=uploads.get(id);
        if(upload==null) throw new IOException("该文件不属于接收缓存");
        synchronized(upload.lifecycle) {
            if(upload.activeRequests!=0||upload.maintenance)throw new IOException("文件正在传输或保存，请完成后清理");
            upload.maintenance=true;
        }
        try {
        synchronized(upload) {
            if(!"complete".equals(upload.status)||upload.finalName==null)throw new IOException("文件尚未接收完成");
            ShareFile file=files.get(id);
            Object guard=file==null?upload:file;
            synchronized(guard) {
                for(Download d:downloads.values())if(id.equals(d.fileId)&&"downloading".equals(d.status))throw new IOException("文件正在发送，请完成或取消后清理");
                File target=new File(received,upload.finalName).getCanonicalFile();
                if(!target.getParentFile().equals(received.getCanonicalFile()))throw new IOException("接收文件路径无效");
                // A tombstone makes a failed deletion recoverable and prevents stale metadata being shared.
                String previous=upload.status;upload.status="deleting";persist(upload);
                try {Files.deleteIfExists(target.toPath());}
                catch(IOException e){upload.status=previous;persist(upload);throw new IOException("接收缓存无法删除，请稍后重试",e);}
                files.remove(id);tickets.entrySet().removeIf(e->id.equals(e.getValue().fileId));
                Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(metaFile(upload).toPath());uploads.remove(id);
            }
        }
        } finally {synchronized(upload.lifecycle){upload.maintenance=false;}}
    }

    /**
     * Rebinds a completed share to its verified exported source, then frees its private duplicate.
     * The caller must first durably save the exported URI and verify the complete exported bytes.
     * A tiny bounded completion receipt remains so a lost completion response can safely be retried.
     * An active download retains its private source; the caller can retry cleanup on a later sweep.
     * Removing the share before cleanup keeps it hidden; cleanup never recreates a removed share.
     */
    public void releaseReceivedCache(String id,Source replacement) throws IOException {
        Objects.requireNonNull(replacement);
        Upload upload=uploads.get(id);
        if(upload==null)throw new IOException("该文件不属于接收缓存");
        synchronized(upload) {
            ShareFile file=files.get(id);
            Object guard=file==null?upload:file;
            synchronized(guard) {
                if("released".equals(upload.status)) {if(file!=null){file.source=replacement;file.temporary=false;}return;}
                if(!"complete".equals(upload.status)||upload.finalName==null)throw new IOException("文件尚未接收完成");
                for(Download d:downloads.values())if(id.equals(d.fileId)&&"downloading".equals(d.status))throw new IOException("文件正在发送，请稍后清理");
                File target=ownedFile(received,upload.finalName);
                upload.saveRequested=true;upload.status="releasing";persist(upload);
                if(file!=null){file.source=replacement;file.temporary=false;}
                try {Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(target.toPath());}
                catch(IOException e) {upload.status="complete";persist(upload);throw new IOException("接收缓存无法删除，请稍后重试",e);}
                upload.status="released";upload.finalName=null;persist(upload);
            }
        }
        pruneReceipts();
    }

    /**
     * Removes idle upload parts and completed, unsaved private relay files. Age expiry applies at maxIdleMs; budget
     * eviction additionally requires 30 minutes of inactivity. Active requests and verification
     * are never interrupted. Passing zero for both limits explicitly clears all idle partials.
     * Recent/resuming transfers may exceed the budget; explicitly retained user files are never swept.
     */
    public long cleanupStaleUploads(long maxIdleMs,long budgetBytes,long currentTime) throws IOException {
        if(maxIdleMs<0||budgetBytes<0||currentTime<0)throw new IllegalArgumentException("Invalid cleanup policy");
        List<Upload> candidates=new ArrayList<>();long idleBytes=0,reclaimed=0;
        for(Upload upload:uploads.values()) {
            synchronized(upload.lifecycle) {
                if(upload.activeRequests!=0||upload.maintenance||!isReclaimable(upload))continue;
                long bytes=cacheFile(upload).length();idleBytes=saturatedAdd(idleBytes,bytes);candidates.add(upload);
            }
        }
        candidates.sort(Comparator.comparingLong(upload->upload.updatedAt));
        for(Upload upload:candidates) {
            synchronized(upload.lifecycle) {
                if(uploads.get(upload.id)!=upload||upload.activeRequests!=0||upload.maintenance||!isReclaimable(upload))continue;
                long idleFor=Math.max(0,currentTime-upload.updatedAt);
                if(idleFor<maxIdleMs&&(idleBytes<=budgetBytes||idleFor<IDLE_BUDGET_GRACE))continue;
                upload.maintenance=true;
            }
            try {
                synchronized(upload) {
                    if(uploads.get(upload.id)!=upload||!isReclaimable(upload))continue;
                    long bytes=cacheFile(upload).length();
                    if(isTemporaryReceived(upload))deleteTemporaryReceived(upload);
                    else {cancel(upload);uploads.remove(upload.id,upload);}
                    reclaimed=saturatedAdd(reclaimed,bytes);idleBytes=Math.max(0,idleBytes-bytes);
                }
            } finally {synchronized(upload.lifecycle){upload.maintenance=false;}}
        }
        reclaimed=saturatedAdd(reclaimed,cleanupOrphanUploadBytes());pruneReceipts();return reclaimed;
    }

    /** Current idle temporary bytes eligible for an explicit clear; excludes active and saved files. */
    public long reclaimableCacheBytes() {
        long bytes=0;
        for(Upload upload:uploads.values())synchronized(upload.lifecycle) {
            if(upload.activeRequests==0&&!upload.maintenance&&isReclaimable(upload))bytes=saturatedAdd(bytes,cacheFile(upload).length());
        }
        return bytes;
    }

    /** Explicitly retained or unclassified received bytes; temporary relay files are counted as cache. */
    public long protectedReceivedBytes() {
        long bytes=0;File[] stored=received.listFiles();
        Set<String> temporary=new HashSet<>();
        for(Upload upload:uploads.values())if(isTemporaryReceived(upload)&&upload.finalName!=null)temporary.add(upload.finalName);
        if(stored!=null)for(File file:stored)if(file.isFile()&&!Files.isSymbolicLink(file.toPath())&&!temporary.contains(file.getName()))bytes=saturatedAdd(bytes,file.length());
        return bytes;
    }

    /** Caller holds upload monitor and an exclusive lifecycle maintenance claim. */
    private void deleteTemporaryReceived(Upload upload) throws IOException {
        File target=ownedFile(received,upload.finalName);
        upload.status="deleting";persist(upload);
        try {Files.deleteIfExists(target.toPath());}
        catch(IOException failure){upload.status="complete";persist(upload);throw failure;}
        files.remove(upload.id);tickets.entrySet().removeIf(entry->upload.id.equals(entry.getValue().fileId));
        Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(metaFile(upload).toPath());uploads.remove(upload.id,upload);
    }

    public void sendText(String text) { addText(text,"本机"); }
    public synchronized void clearTexts() { texts.clear(); }
    public List<ClientInfo> connectedClients() {
        List<ClientInfo> result=new ArrayList<>();
        for (Session s:sessions.values()) if (valid(s)) result.add(clientCopy(s));
        result.sort(Comparator.comparingLong(c -> c.connectedAt));
        return result;
    }
    public void revokeClient(String id) {
        for(Session session:sessions.values()) if(session.id.equals(id)) {
            session.revoked=true;
            for(Socket socket:session.connections)try{socket.close();}catch(IOException ignored){ }
        }
        sessions.entrySet().removeIf(e -> e.getValue().id.equals(id));
        tickets.entrySet().removeIf(e -> e.getValue().clientId.equals(id));
    }
    public void cancelUpload(String id) throws IOException {
        Upload upload=uploads.get(id);
        if (upload == null) return;
        requestCancel(upload);
        synchronized (upload) { if(uploads.get(id)==upload)cancel(upload); }
    }
    public void cancelDownload(String id) {
        Download download=downloads.get(id);
        if (download!=null) {
            download.cancelled=true; download.error="已取消";
            Socket socket=download.socket;if(socket!=null)try{socket.close();}catch(IOException ignored){ }
        }
    }

    /** Safe state snapshot. Sessions and upload recovery keys are deliberately excluded. */
    public String state() {
        JsonObject result=new JsonObject();
        result.addProperty("serverName","FileFlash");
        List<ShareFile> shared=new ArrayList<>(files.values());
        shared.sort(Comparator.comparingLong(f -> -f.createdAt));
        result.add("files",GSON.toJsonTree(shared));
        synchronized(this) { result.add("texts",GSON.toJsonTree(texts)); }
        JsonArray tasks=new JsonArray();
        List<Upload> sorted=new ArrayList<>(uploads.values());
        sorted.sort(Comparator.comparingLong(u -> -u.createdAt));
        int terminalHistory=0;
        for (Upload upload:sorted) {
            if(isComplete(upload)||"cancelled".equals(upload.status)) {if(terminalHistory++>=256)continue;}
            tasks.add(uploadJson(upload,false));
        }
        result.add("uploads",tasks);
        result.add("clients",GSON.toJsonTree(connectedClients()));
        List<Download> active=new ArrayList<>(downloads.values());
        active.sort(Comparator.comparingLong(d -> -d.startedAt));
        result.add("downloads",GSON.toJsonTree(active));
        return result.toString();
    }

    private void handle(HttpServer.Request request,HttpServer.Response response) throws Exception {
        checkHostAndOrigin(request);
        String path=request.path;
        if (path.equals("/api/pair")) {
            requireMethod(request,"POST"); pair(request,response); return;
        }
        if (path.startsWith("/api/downloads/")) {
            requireDownloadMethod(request);
            String key=lastId(path);
            Ticket ticket=tickets.get(key);
            if (ticket==null || ticket.expiresAt < now()) { tickets.remove(key); throw error(401,"ticket_expired","下载链接已失效，请重新下载"); }
            Session session=sessionById(ticket.clientId);
            if (!valid(session)) throw error(401,"session_expired","连接已失效，请重新配对");
            session.lastSeen=now();
            session.connections.add(request.socket);
            try {download(request,response,ticket.fileId,session);}finally{session.connections.remove(request.socket);}return;
        }
        if (!path.startsWith("/api/")) { serveAsset(request,response); return; }
        Session session=authenticate(request);
        session.connections.add(request.socket);
        try {
        if (path.equals("/api/diagnostics")) { requireMethod(request,"GET");response.json(200,diagnostics().toString());return; }
        if (path.equals("/api/diagnostics/probe")) {diagnosticProbe(request,response);return;}
        if (path.equals("/api/state")) { requireMethod(request,"GET");response.json(200,state());return; }
        if (path.equals("/api/session")) {
            requireMethod(request,"DELETE");response.json(200,"{\"ok\":true}");revokeClient(session.id);return;
        }
        if (path.equals("/api/uploads")) { requireMethod(request,"POST");createUpload(request,response,session);return; }
        if (path.equals("/api/shortcuts/files")) { requireMethod(request,"POST");shortcutUpload(request,response,session);return; }
        if (path.startsWith("/api/uploads/")) { uploadRoute(request,response,session);return; }
        if (path.startsWith("/api/files/")) {
            String[] parts=path.split("/");
            if (parts.length==5 && parts[4].equals("ticket")) {
                requireMethod(request,"POST"); getFile(parts[3]);
                if (tickets.size()>256) tickets.entrySet().removeIf(e -> e.getValue().expiresAt < now());
                if (tickets.size()>256) throw error(429,"too_many_tickets","下载任务过多，请稍后重试");
                String key=randomToken(24); Ticket ticket=new Ticket(parts[3],session.id,now()+TICKET_LIFETIME);tickets.put(key,ticket);
                JsonObject json=new JsonObject();json.addProperty("url","/api/downloads/"+key);json.addProperty("expiresAt",ticket.expiresAt);
                response.json(200,json.toString());return;
            }
            if (parts.length==4) { requireDownloadMethod(request);download(request,response,parts[3],session);return; }
        }
        if (path.equals("/api/texts")) {
            requireMethod(request,"POST");JsonObject json=readJson(request,JSON_LIMIT);
            String value=string(json,"text",true);
            if(value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length>512*1024) throw error(400,"invalid_text","文字不能为空，且最多 512 KiB");
            TextMessage text=addText(value,session.name);
            response.json(201,GSON.toJson(text));return;
        }
        if (path.startsWith("/api/texts/")) {
            requireMethod(request,"DELETE");String id=lastId(path);
            synchronized(this) { texts.removeIf(t -> t.id.equals(id)); }
            response.json(200,"{\"ok\":true}");return;
        }
        if (path.startsWith("/api/downloads-cancel/")) {
            requireMethod(request,"DELETE");Download d=downloads.get(lastId(path));
            if (d!=null && !session.id.equals(d.clientId)) throw error(403,"not_owner","无法取消其他设备的任务");
            if(d!=null) cancelDownload(d.id); response.json(200,"{\"ok\":true}");return;
        }
        throw error(404,"not_found","接口不存在");
        } finally {session.connections.remove(request.socket);}
    }

    /** This snapshot is authenticated, memory-only and contains no addresses, file names or credentials. */
    private JsonObject diagnostics() {
        JsonObject result=new JsonObject();result.addProperty("schema",1);result.addProperty("running",running);
        result.add("transport",http.diagnostics());
        JsonObject engine=new JsonObject();engine.addProperty("uploadSlotsAvailable",uploadSlots.availablePermits());
        engine.addProperty("downloadSlotsAvailable",downloadSlots.availablePermits());
        int active=0,maintenance=0,incomplete=0;
        for(Upload upload:uploads.values()) {if(upload.activeRequests>0)active++;if(upload.maintenance)maintenance++;if(isIncomplete(upload))incomplete++;}
        engine.addProperty("uploadsActive",active);engine.addProperty("uploadsMaintaining",maintenance);engine.addProperty("uploadsIncomplete",incomplete);
        result.add("engine",engine);return result;
    }

    /** Tiny opt-in browser interoperability probe: no task, file, payload echo or persistent record. */
    private void diagnosticProbe(HttpServer.Request request,HttpServer.Response response) throws IOException {
        if(!request.method.equals("POST")&&!request.method.equals("PUT"))throw error(405,"method_not_allowed","不支持此操作");
        if(request.length>512*1024)throw error(413,"probe_size","诊断数据最多 512 KiB");
        if(!uploadSlots.tryAcquire())throw error(429,"transfer_limit","传输任务繁忙，请稍后重试");
        try {
            byte[] bytes=request.readBody(512*1024);
            JsonObject result=new JsonObject();result.addProperty("length",bytes.length);result.addProperty("sha256",hex(digest().digest(bytes)));
            response.json(200,result.toString());
        } finally {uploadSlots.release();}
    }

    private void pair(HttpServer.Request request,HttpServer.Response response) throws IOException {
        ratePair(request.socket.getInetAddress().getHostAddress());
        JsonObject body=readJson(request,16*1024);
        if (!constantEquals(string(body,"token",true),pairToken)) throw error(401,"invalid_token","配对码无效，请扫描新的二维码");
        sessions.entrySet().removeIf(e -> !valid(e.getValue()));
        if (sessions.size()>=16) throw error(429,"client_limit","连接设备过多，请先断开其他设备");
        Session session=new Session();
        session.id=randomToken(18);session.secret=randomToken(32);session.name=shortName(string(body,"name",false));
        session.connectedAt=now();session.lastSeen=now(); sessions.put(session.secret,session);
        JsonObject json=new JsonObject();json.addProperty("session",session.secret);json.addProperty("clientId",session.id);
        json.addProperty("chunkSize",CHUNK_SIZE);json.addProperty("name","FileFlash");
        response.json(201,json.toString());
    }

    private Session authenticate(HttpServer.Request request) throws HttpError {
        String authorization=request.header("authorization");
        Session session=authorization!=null && authorization.startsWith("Bearer ") ? sessions.get(authorization.substring(7)) : null;
        if (!valid(session)) throw error(401,"session_expired","连接已失效，请重新配对");
        session.lastSeen=now();
        synchronized(session) {
            if(now()-session.windowStart>60_000) {session.windowStart=now();session.requests=0;}
            if(++session.requests>6000) throw error(429,"request_limit","请求过于频繁，请稍后重试");
        }
        return session;
    }

    private void createUpload(HttpServer.Request request,HttpServer.Response response,Session session) throws IOException {
        JsonObject body=readJson(request,16*1024);
        String name=safeName(string(body,"name",true));long size=nonNegativeLong(body,"size");
        String mime=safeMime(string(body,"mime",false));String sha=optionalSha(body,"sha256");
        boolean restartOnly=optionalBoolean(body,"restartOnly");if(restartOnly)sha=null;
        synchronized(uploads) {
            if(sha!=null) for(Upload u:uploads.values()) if(!u.restartOnly) {
                if(session.id.equals(u.owner) && u.name.equals(name) && u.size==size && sha.equals(u.sha256) && u.status.equals("uploading")) {
                    response.json(200,uploadJson(u,true).toString());return;
                }
            }
            Upload upload=allocateUpload(name,size,mime,sha,restartOnly,session,request.trace);
            uploads.put(upload.id,upload);
            response.json(201,uploadJson(upload,true).toString());
        }
    }

    /** Caller holds the creation guard; byte reservations include all active upload protocols. */
    private Upload allocateUpload(String name,long size,String mime,String sha,boolean restartOnly,Session session,TransportDiagnostics.Trace trace) throws IOException {
        long pending=0;int count=0;
        for(Upload u:uploads.values()) {
            // Streaming tasks publish a volatile progress snapshot. Never hold the global
            // creation guard while waiting for their monitor and an entire request body.
            if(u.restartOnly) {
                if(!isComplete(u)&&!"cancelled".equals(u.status)) {
                    count++;long remaining=u.unknownLength?0:u.size-u.offset;
                    if(remaining>Long.MAX_VALUE-pending)throw error(507,"storage_full","剩余存储空间不足");pending+=remaining;
                }
                continue;
            }
            if(!isComplete(u) && !u.status.equals("cancelled")) {
                count++;long remaining=u.size-u.reservationOffset;
                if(remaining>Long.MAX_VALUE-pending) throw error(507,"storage_full","剩余存储空间不足");pending+=remaining;
            }
        }
        if(count>=128) throw error(429,"upload_limit","未完成任务过多，请先取消部分任务");
        if(files.size()+count>=2048)throw error(429,"file_limit","文件记录过多，请先清理接收缓存");
        long usable=root.getUsableSpace();
        if(size>usable || pending>usable-size || usable-size-pending<4*1024*1024)
            throw error(507,"storage_full","剩余存储空间不足");
        Upload upload=new Upload();upload.id=randomToken(18);upload.resumeKey=randomToken(24);upload.owner=session.id;
        upload.name=name;upload.size=size;upload.mime=mime;upload.sha256=sha;upload.status="uploading";
        upload.temporaryReceived=true;upload.restartOnly=restartOnly;
        upload.createdAt=now();upload.updatedAt=upload.createdAt;
        try {
            try(RandomAccessFile data=new RandomAccessFile(partFile(upload),"rw")) {if(!restartOnly)data.getFD().sync();}
            persist(upload,trace);
        } catch(IOException failure) {
            try {Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(metaFile(upload).toPath());Files.deleteIfExists(new File(incoming,upload.id+".json.tmp").toPath());}
            catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
        return upload;
    }

    /** The system Shortcuts app sends one file body; no client checksum or resumable key is needed. */
    private void shortcutUpload(HttpServer.Request request,HttpServer.Response response,Session session) throws IOException {
        if(!request.chunked&&request.header("content-length")==null)throw error(411,"length_required","上传需要 Content-Length");
        String name=shortcutName(request.rawQuery);
        String type=request.header("content-type");
        String mime=safeMime(type==null?null:type.split(";",2)[0].trim());
        if(!uploadSlots.tryAcquire())throw error(429,"transfer_limit","传输任务繁忙，请稍后重试");
        Upload upload=null;
        try {
            synchronized(uploads) {
                upload=allocateUpload(name,request.chunked?0:request.length,mime,null,true,session,request.trace);
                // Publish the active claim with the record so cleanup cannot remove it before streaming starts.
                upload.unknownLength=request.chunked;upload.activeRequests=1;uploads.put(upload.id,upload);
            }
            synchronized(upload) {streamUpload(request,response,upload,session);}
        } finally {
            if(upload!=null)synchronized(upload.lifecycle){upload.activeRequests--;}
            uploadSlots.release();
        }
    }

    /** Decode only this bounded query value, never a routing or filesystem path. Plus is a literal URL character. */
    private static String shortcutName(String query) throws HttpError {
        if(query==null||!query.startsWith("name=")||query.indexOf('&')>=0)throw error(400,"invalid_name","缺少有效文件名");
        if(query.length()>4096)throw error(413,"name_too_large","文件名过长");
        String encoded=query.substring(5);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(int i=0;i<encoded.length();i++) {
            char next=encoded.charAt(i);
            if(next=='%') {
                if(i+2>=encoded.length())throw error(400,"invalid_name","文件名编码无效");
                int high=Character.digit(encoded.charAt(++i),16),low=Character.digit(encoded.charAt(++i),16);
                if(high<0||low<0)throw error(400,"invalid_name","文件名编码无效");bytes.write((high<<4)|low);
            } else {
                if(next>127)throw error(400,"invalid_name","文件名须使用 UTF-8 URL 编码");bytes.write(next);
            }
        }
        if(bytes.size()>1024)throw error(413,"name_too_large","文件名过长");
        String name;
        try {name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();}
        catch(CharacterCodingException invalid){throw error(400,"invalid_name","文件名编码无效");}
        if(name.trim().isEmpty()||name.equals(".")||name.equals(".."))throw error(400,"invalid_name","文件名无效");
        for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i))||name.charAt(i)=='/'||name.charAt(i)=='\\')throw error(400,"invalid_name","文件名不能包含路径或控制字符");
        String safe=safeName(name),original=name.trim();
        while(original.endsWith("."))original=original.substring(0,original.length()-1);
        int dot=original.lastIndexOf('.');
        if(dot>0&&original.getBytes(StandardCharsets.UTF_8).length>180) {
            String extension=original.substring(dot);
            if(extension.matches("\\.[A-Za-z0-9]{1,32}"))
                return fitShortcutName(safeName(original.substring(0,dot)),extension);
        }
        return safe;
    }

    private void uploadRoute(HttpServer.Request request,HttpServer.Response response,Session session) throws IOException {
        String[] parts=request.path.split("/");
        if(parts.length<4 || parts.length>5 || !validId(parts[3])) throw error(404,"not_found","上传任务不存在");
        Upload upload=uploads.get(parts[3]);
        if(upload==null) throw error(404,"not_found","上传任务不存在");
        String key=request.header("x-upload-key");
        if(!session.id.equals(upload.owner) && !constantEquals(key,upload.resumeKey)) throw error(403,"upload_key_required","需要原任务的恢复凭据，请重新选择原文件");
        if(parts.length==4 && request.method.equals("DELETE")) requestCancel(upload);
        boolean prefix=parts.length==5&&parts[4].equals("prefix")&&request.method.equals("GET");
        boolean heavy=request.method.equals("PUT")||(parts.length==5&&request.method.equals("POST"))||prefix;
        if(heavy&&!uploadSlots.tryAcquire())throw error(429,"transfer_limit","传输任务繁忙，请稍后重试");
        synchronized(upload.lifecycle) {
            if(upload.maintenance||uploads.get(upload.id)!=upload) {
                if(heavy)uploadSlots.release();throw error(404,"upload_expired","上传缓存已清理，请重新选择文件发送");
            }
            upload.activeRequests++;
        }
        try {
        request.trace.mark("upload-lock-wait");long lockStarted=System.nanoTime();
        synchronized(upload) {
            request.trace.duration("uploadLockWait",lockStarted);request.trace.mark("upload-lock-acquired");
            if(parts.length==5) {
                if(parts[4].equals("stream")) {
                    requireMethod(request,"POST");streamUpload(request,response,upload,session);return;
                }
                if(upload.restartOnly)throw error(409,"restart_only","该任务失败后需重新上传");
                if(parts[4].equals("chunk")) {
                    requireMethod(request,"POST");appendChunk(request,response,upload,session);return;
                }
                if(parts[4].equals("prefix")) {
                    requireMethod(request,"GET");uploadPrefix(response,upload,session);return;
                }
                if(!parts[4].equals("complete")) throw error(404,"not_found","接口不存在");
                requireMethod(request,"POST");completeUpload(request,response,upload,session);return;
            }
            if(request.method.equals("GET")) {response.json(200,uploadJson(upload,true).toString());return;}
            if(request.method.equals("DELETE")) {
                if(upload.restartOnly&&isTemporaryReceived(upload)) {
                    synchronized(upload.lifecycle) {if(upload.activeRequests!=1)throw error(409,"file_busy","文件正在使用，请稍后清理");upload.maintenance=true;}
                    try {deleteTemporaryReceived(upload);upload.status="cancelled";}finally{synchronized(upload.lifecycle){upload.maintenance=false;}}
                } else cancel(upload);
                response.json(200,uploadJson(upload,false).toString());return;
            }
            if(upload.restartOnly)throw error(409,"restart_only","该任务失败后需重新上传");
            requireMethod(request,"PUT"); appendChunk(request,response,upload,session);
        }
        } finally {synchronized(upload.lifecycle){upload.activeRequests--;}if(heavy)uploadSlots.release();}
    }

    /** Hashes only durably acknowledged bytes so a browser can validate a reselected resume file. */
    private void uploadPrefix(HttpServer.Response response,Upload upload,Session session) throws IOException {
        if(!isIncomplete(upload))throw error(409,"upload_closed","任务已结束，请刷新传输进度");
        long offset=upload.offset,remaining=offset;
        MessageDigest digest=digest();byte[] buffer=new byte[128*1024];
        try(InputStream input=new FileInputStream(partFile(upload))) {
            while(remaining>0) {
                if(!running||!valid(session)||upload.cancelRequested||Thread.currentThread().isInterrupted())
                    throw error(409,"verification_interrupted","断点校验已中断，请重试");
                int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(count<0)throw error(409,"resume_data_missing","断点文件不完整，请清理该任务后重新发送");
                if(count==0)continue;
                digest.update(buffer,0,count);remaining-=count;
            }
        }
        JsonObject result=new JsonObject();result.addProperty("id",upload.id);result.addProperty("size",upload.size);
        result.addProperty("offset",offset);result.addProperty("prefixSha256",hex(digest.digest()));response.json(200,result.toString());
    }

    private void appendChunk(HttpServer.Request request,HttpServer.Response response,Upload upload,Session session) throws IOException {
        if(!upload.status.equals("uploading") && !upload.status.equals("failed")) throw error(409,"upload_closed","任务已结束");
        long offset=headerLong(request,"x-offset");String sha=request.header("x-chunk-sha256");
        if(!isSha(sha)) throw error(400,"invalid_checksum","分块需要有效的 SHA-256 校验值");sha=sha.toLowerCase(Locale.ROOT);
        if(request.length<=0 || request.length>CHUNK_SIZE) throw error(413,"chunk_size","分块大小须为 1 至 2097152 字节");
        if(offset>upload.size || request.length>upload.size-offset) throw error(409,"size_mismatch","分块超过声明的文件大小");
        boolean duplicate=offset==upload.lastOffset && request.length==upload.lastLength && sha.equals(upload.lastSha) && upload.offset==offset+request.length;
        if(offset!=upload.offset && !duplicate) throw error(409,"offset_mismatch","分块位置不匹配，请查询任务进度后重试");
        // A single chunk is the only bounded file-data allocation (maximum 2 MiB per worker).
        byte[] chunk;
        upload.activeSocket=request.socket;
        try {chunk=request.readBody(CHUNK_SIZE);} finally {upload.activeSocket=null;}
        request.trace.mark("chunk-hashing");long hashStarted=System.nanoTime();
        String actual=hex(digest().digest(chunk));request.trace.duration("chunkHash",hashStarted);
        if(!sha.equals(actual)) throw error(422,"chunk_checksum","分块校验失败，请重试");
        if(!valid(session) || !running) throw error(401,"session_expired","连接已失效");
        if(upload.cancelRequested) throw error(409,"upload_cancelled","任务已取消");
        if(!duplicate) {
            if(root.getUsableSpace()<chunk.length+1024L*1024) throw error(507,"storage_full","剩余存储空间不足");
            try(RandomAccessFile file=new RandomAccessFile(partFile(upload),"rw")) {
                request.trace.mark("file-writing");long writeStarted=System.nanoTime();
                // Metadata failure can leave a synchronized but unacknowledged tail.
                // Re-reserve it before truncation, atomically with every creation/write budget check.
                synchronized(uploads){upload.reservationOffset=upload.offset;file.setLength(upload.offset);}
                file.seek(upload.offset);file.write(chunk);request.trace.duration("fileWrite",writeStarted);
                request.trace.mark("data-syncing");long syncStarted=System.nanoTime();
                try {file.getFD().sync();}finally{request.trace.duration("dataSync",syncStarted);}
                upload.reservationOffset=offset+chunk.length;
            } catch(IOException e) {upload.status="failed";upload.error="文件写入失败，请检查剩余空间后重试";persist(upload);throw error(507,"write_failed",upload.error);}
            // Save committed offset only after the data has reached stable storage.
            long previous=upload.offset;
            upload.lastOffset=offset;upload.lastLength=chunk.length;upload.lastSha=sha;upload.offset+=chunk.length;
            upload.updatedAt=now();upload.status="uploading";upload.error=null;
            try {persist(upload,request.trace);} catch(IOException e) {upload.offset=previous;throw e;}
        }
        request.trace.mark(duplicate?"duplicate-confirmed":"chunk-committed");
        response.json(200,uploadJson(upload,false).toString());
    }

    /** A browser can opt out of checkpoints: one bounded-buffer request, one final data sync. */
    private void streamUpload(HttpServer.Request request,HttpServer.Response response,Upload upload,Session session) throws IOException {
        if(!upload.restartOnly)throw error(409,"stream_mode_required","请创建连续上传任务");
        if(!isIncomplete(upload)||upload.offset!=0)throw error(409,"upload_closed","任务已结束，请重新上传");
        boolean succeeded=false;
        upload.activeSocket=request.socket;
        try {
            if(!request.chunked&&request.header("content-length")==null)throw error(400,"length_required","连续上传需要 Content-Length");
            if(!request.chunked&&request.length!=upload.size)throw error(409,"size_mismatch","上传长度与文件大小不符");
            checkStreamActive(upload,session);
            MessageDigest hash=digest();byte[] buffer=new byte[128*1024];
            request.trace.mark("stream-receiving");
            try(FileOutputStream output=new FileOutputStream(partFile(upload),false)) {
                request.beginStreamBody();
                while(request.chunked||request.consumed<request.length) {
                    checkStreamActive(upload,session);
                    int count=request.readStreamBody(buffer);if(count<0)break;if(count==0)continue;
                    checkStreamActive(upload,session);
                    long started=System.nanoTime();
                    if(request.chunked)writeUnknownLength(upload,output,buffer,count,request.consumed);
                    else output.write(buffer,0,count);
                    request.trace.addDuration("streamWrite",started);
                    started=System.nanoTime();hash.update(buffer,0,count);request.trace.addDuration("streamHash",started);
                    // This progress is intentionally not a resumable or durable checkpoint.
                    upload.offset=request.consumed;upload.updatedAt=now();
                }
                checkStreamActive(upload,session);request.trace.mark("stream-received");
                request.trace.mark("data-syncing");long started=System.nanoTime();
                try {output.getFD().sync();}finally{request.trace.duration("dataSync",started);}
            }
            checkStreamActive(upload,session);
            if(request.chunked){upload.size=request.consumed;upload.unknownLength=false;}
            // Shortcuts can supply a media title without its original extension and a generic MIME.
            // Resolve metadata from a bounded prefix before persisting/publishing the completed share.
            if(request.path.equals("/api/shortcuts/files"))completeShortcutFilename(upload);
            synchronized(received) {
                checkStreamActive(upload,session);request.trace.mark("stream-promoting");
                upload.sha256=hex(hash.digest());upload.finalName=uniqueName(upload.name);upload.status="promoting";
                persist(upload,request.trace);move(partFile(upload),new File(received,upload.finalName));
                checkStreamActive(upload,session);
                upload.status="complete";upload.name=upload.finalName;upload.updatedAt=now();persist(upload,request.trace);addReceived(upload);
            }
            response.json(200,uploadJson(upload,false).toString());succeeded=true;
        } finally {
            upload.activeSocket=null;
            if(!succeeded) {
                try {discardRestartOnly(upload);}catch(IOException cleanup) {
                    // Keep restartOnly metadata so recovery can retry removal after an I/O failure.
                    request.trace.error(cleanup);
                }
            }
        }
    }

    /** Unknown streams may consume only free bytes left after every declared upload reservation. */
    private void writeUnknownLength(Upload upload,FileOutputStream output,byte[] buffer,int count,long total) throws IOException {
        synchronized(uploads) {
            long pending=0;
            for(Upload other:uploads.values()) {
                if(other.restartOnly) {
                    if(!other.unknownLength&&!isComplete(other)&&!"cancelled".equals(other.status)) {
                        long remaining=other.size-other.offset;
                        if(remaining>Long.MAX_VALUE-pending)throw error(507,"storage_full","剩余存储空间不足");pending+=remaining;
                    }
                } else {
                    if(!isComplete(other)&&!"cancelled".equals(other.status)) {
                        long remaining=other.size-other.reservationOffset;
                        if(remaining>Long.MAX_VALUE-pending)throw error(507,"storage_full","剩余存储空间不足");pending+=remaining;
                    }
                }
            }
            long usable=root.getUsableSpace();
            if(pending>usable||usable-pending<count+4L*1024*1024)throw error(507,"storage_full","剩余存储空间不足");
            // The same guard covers new reservations and this bounded write; it is never held while reading the network.
            output.write(buffer,0,count);upload.size=total;
        }
    }

    private void checkStreamActive(Upload upload,Session session) throws HttpError {
        if(!running||!valid(session))throw error(401,"session_expired","连接已失效，请重新上传");
        if(upload.cancelRequested||Thread.currentThread().isInterrupted())throw error(409,"upload_cancelled","上传已取消");
    }

    /** Caller holds the task monitor; only this task's private bytes may be removed. */
    private void discardRestartOnly(Upload upload) throws IOException {
        if(!upload.restartOnly)return;
        if(upload.saveRequested||"released".equals(upload.status)||"releasing".equals(upload.status))
            throw error(409,"already_saved","文件已经保存");
        if(isComplete(upload))synchronized(upload.lifecycle) {
            if(upload.activeRequests>1)throw error(409,"file_busy","文件正在使用，请稍后清理");
        }
        upload.cancelRequested=true;
        Files.deleteIfExists(partFile(upload).toPath());
        if(upload.finalName!=null)Files.deleteIfExists(ownedFile(received,upload.finalName).toPath());
        upload.status="cancelled";upload.error=null;
        files.remove(upload.id);tickets.entrySet().removeIf(entry->upload.id.equals(entry.getValue().fileId));
        Files.deleteIfExists(metaFile(upload).toPath());Files.deleteIfExists(new File(incoming,upload.id+".json.tmp").toPath());
        uploads.remove(upload.id,upload);
        upload.offset=0;
    }

    private void completeUpload(HttpServer.Request request,HttpServer.Response response,Upload upload,Session session) throws IOException {
        if(isComplete(upload)) {response.json(200,uploadJson(upload,false).toString());return;}
        if(upload.status.equals("cancelled")) throw error(410,"upload_cancelled","任务已取消");
        JsonObject body=readJson(request,16*1024);String provided=optionalSha(body,"sha256");
        String expected=provided!=null?provided:upload.sha256;
        if(expected==null) throw error(400,"checksum_required","完成文件需要完整 SHA-256 校验值");
        if(upload.sha256!=null && !constantEquals(upload.sha256,expected)) throw error(422,"checksum_changed","完整文件校验值与任务声明不一致");
        if(upload.offset!=upload.size) throw error(409,"upload_incomplete","文件尚未传输完整");
        upload.status="verifying";upload.error=null;upload.updatedAt=now();persist(upload,request.trace);
        String actual;
        request.trace.mark("file-hashing");long hashStarted=System.nanoTime();
        try {actual=hashFile(partFile(upload),()->running && valid(session) && !upload.cancelRequested);}
        catch(IOException e) {upload.status="uploading";upload.error="校验中断，请重试";persist(upload);throw error(409,"verification_interrupted",upload.error);}
        finally {request.trace.duration("fileHash",hashStarted);}
        if(!actual.equals(expected)) {
            // Corrupted completed uploads cannot be appended to. Start the same task from zero.
            try(RandomAccessFile file=new RandomAccessFile(partFile(upload),"rw")) {
                synchronized(uploads){upload.reservationOffset=0;file.setLength(0);}
                file.getFD().sync();
            }
            upload.offset=0;upload.lastOffset=-1;upload.lastLength=0;upload.lastSha=null;
            upload.status="failed";upload.error="完整文件校验失败，已重置，请重新发送";persist(upload);
            throw error(422,"file_checksum",upload.error);
        }
        synchronized(received) {
            upload.sha256=actual;upload.finalName=uniqueName(upload.name);upload.status="promoting";persist(upload,request.trace);
            move(partFile(upload),new File(received,upload.finalName));
            upload.status="complete";upload.name=upload.finalName;upload.updatedAt=now();persist(upload,request.trace);addReceived(upload);
        }
        response.json(200,uploadJson(upload,false).toString());
    }

    private void cancel(Upload upload) throws IOException {
        if(isComplete(upload)) throw error(409,"already_complete","文件已接收，无法取消");
        if(upload.restartOnly) {discardRestartOnly(upload);return;}
        upload.status="cancelled";upload.error=null;upload.updatedAt=now();persist(upload);
        Files.deleteIfExists(partFile(upload).toPath());
        Files.deleteIfExists(metaFile(upload).toPath());
        pruneCancelled();
    }

    private void pruneCancelled() {
        List<Upload> cancelled=new ArrayList<>();
        for(Upload task:uploads.values())if("cancelled".equals(task.status))cancelled.add(task);
        cancelled.sort(Comparator.comparingLong(task -> -task.updatedAt));
        for(int i=256;i<cancelled.size();i++) {
            Upload task=cancelled.get(i);
            if(uploads.remove(task.id,task))try{Files.deleteIfExists(metaFile(task).toPath());}catch(IOException ignored){ }
        }
    }

    private void pruneReceipts() throws IOException {
        List<Upload> receipts=new ArrayList<>();
        for(Upload task:uploads.values())if("released".equals(task.status))receipts.add(task);
        receipts.sort(Comparator.comparingLong((Upload task)->-task.updatedAt).thenComparing(task->task.id));
        for(int i=RECEIPT_LIMIT;i<receipts.size();i++) {
            Upload task=receipts.get(i);
            synchronized(task.lifecycle) {
                if(task.activeRequests!=0||task.maintenance)continue;
                task.maintenance=true;
            }
            try {synchronized(task) {if("released".equals(task.status)){Files.deleteIfExists(metaFile(task).toPath());uploads.remove(task.id,task);}}}
            finally {synchronized(task.lifecycle){task.maintenance=false;}}
        }
    }

    private long cleanupOrphanUploadBytes() throws IOException {
        long reclaimed=0;
        // Creation holds this guard until its metadata and in-memory identity are committed.
        synchronized(uploads) {
            File[] entries=incoming.listFiles();
            if(entries==null)return 0;
            for(File entry:entries) {
                String name=entry.getName(),id=engineId(name,".part");
                if(id==null)id=engineId(name,".json.tmp");
                if(id==null||uploads.containsKey(id)||new File(incoming,id+".json").exists()||entry.isDirectory())continue;
                long size=entry.length();Files.deleteIfExists(entry.toPath());reclaimed=saturatedAdd(reclaimed,size);
            }
        }
        return reclaimed;
    }

    private static void requestCancel(Upload upload) {
        if(isComplete(upload))return;
        upload.cancelRequested=true;
        Socket socket=upload.activeSocket;
        if(socket!=null)try{socket.close();}catch(IOException ignored){ }
    }

    private void download(HttpServer.Request request,HttpServer.Response response,String id,Session session) throws IOException {
        boolean heavy=request.method.equals("GET");
        if(heavy&&!downloadSlots.tryAcquire())throw error(429,"transfer_limit","下载任务繁忙，请稍后重试");
        Upload upload=uploads.get(id);boolean claimed=false;
        try {
            // Claim before looking up/opening the share: cleanup cannot delete between those steps.
            if(upload!=null)synchronized(upload.lifecycle) {
                if(upload.maintenance||uploads.get(id)!=upload)throw error(404,"file_expired","临时文件已清理，请重新上传");
                upload.activeRequests++;claimed=true;
            }
            streamDownload(request,response,id,session,upload);
        } finally {
            if(claimed)synchronized(upload.lifecycle){upload.activeRequests--;}
            if(heavy)downloadSlots.release();
        }
    }

    private void streamDownload(HttpServer.Request request,HttpServer.Response response,String id,Session session,Upload upload) throws IOException {
        ShareFile file=getFile(id);
        long[] range=parseRange(request.header("range"),file.size);
        if(range==null) {
            response.bytes(416,"application/json; charset=utf-8","{\"error\":\"下载范围无效\",\"code\":\"invalid_range\"}".getBytes(StandardCharsets.UTF_8),Collections.singletonMap("Content-Range","bytes */"+file.size));return;
        }
        long offset=range[0],length=range[1];boolean partial=request.header("range")!=null;
        Map<String,String> headers=new LinkedHashMap<>();
        headers.put("Accept-Ranges","bytes");headers.put("Content-Disposition",contentDisposition(file.name));
        headers.put("ETag","\""+file.id+"\"");
        if(file.sha256!=null) headers.put("X-File-SHA256",file.sha256);
        if(partial) headers.put("Content-Range","bytes "+offset+"-"+(offset+length-1)+"/"+file.size);
        if(request.method.equals("HEAD")) {response.begin(partial?206:200,file.mime,length,headers);response.flush();return;}
        Download d=new Download();d.id=randomToken(18);d.fileId=file.id;d.name=file.name;d.size=length;d.clientId=session.id;d.startedAt=now();d.status="downloading";d.socket=request.socket;
        downloads.entrySet().removeIf(e -> !e.getValue().status.equals("downloading") && now()-e.getValue().startedAt>30*60_000);
        if(downloads.size()>128) downloads.entrySet().stream().filter(e -> !e.getValue().status.equals("downloading")).limit(32).map(Map.Entry::getKey).forEach(downloads::remove);
        synchronized(file) {
            if(files.get(file.id)!=file)throw error(404,"file_not_found","文件已移除");
            downloads.put(d.id,d);
        }
        boolean accessed=false;
        try(InputStream input=file.source.open(offset)) {
            if(input==null) throw new FileNotFoundException("文件访问权限已失效");
            accessed=true;
            touchReceived(upload);
            response.begin(partial?206:200,file.mime,length,headers);
            byte[] buffer=new byte[128*1024];long remaining=length;
            while(remaining>0) {
                if(!running || !valid(session) || d.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("传输已取消或连接失效");
                int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(count<0) throw new EOFException("共享文件内容长度已改变");
                if(count==0) continue;
                response.write(buffer,0,count);remaining-=count;d.transferred+=count;session.lastSeen=now();
            }
            response.flush();d.status="complete";
        } catch(IOException e) {
            d.status=d.cancelled?"cancelled":"failed";d.error=d.cancelled?"已取消":"连接中断或文件读取失败，请重试";
            if(!response.started) throw error(410,"source_unavailable","文件不可读取，请在发送端重新选择文件");
            throw e;
        } finally {
            d.socket=null;
            // A long transfer receives a full idle period after it finishes, including reconnects.
            if(accessed)touchReceived(upload);
        }
    }

    private void touchReceived(Upload upload) throws IOException {
        if(upload==null)return;
        synchronized(upload) {
            if(uploads.get(upload.id)==upload&&isTemporaryReceived(upload)) {upload.updatedAt=now();persist(upload);}
        }
    }

    /** Returns [offset,length], null for an unsatisfiable or malformed single range. */
    static long[] parseRange(String header,long size) {
        if(header==null) return new long[]{0,size};
        if(!header.startsWith("bytes=") || header.indexOf(',')>=0 || size==0) return null;
        String range=header.substring(6);int dash=range.indexOf('-');
        if(dash<0 || range.indexOf('-',dash+1)>=0) return null;
        try {
            String left=range.substring(0,dash),right=range.substring(dash+1);
            if(left.isEmpty()) {
                if(!right.matches("[0-9]{1,19}"))return null;long count=Long.parseLong(right);if(count<=0)return null;
                count=Math.min(count,size);return new long[]{size-count,count};
            }
            if(!left.matches("[0-9]{1,19}") || (!right.isEmpty()&&!right.matches("[0-9]{1,19}")))return null;
            long begin=Long.parseLong(left),end=right.isEmpty()?size-1:Long.parseLong(right);
            if(begin>=size || begin>end)return null;end=Math.min(end,size-1);return new long[]{begin,end-begin+1};
        } catch(NumberFormatException e) {return null;}
    }

    private void serveAsset(HttpServer.Request request,HttpServer.Response response) throws IOException {
        requireDownloadMethod(request);
        boolean shortcut=request.path.equals("/FileFlash.shortcut");
        String name=request.path.equals("/")?"index.html":request.path.substring(1);
        if(!shortcut&&!Arrays.asList("index.html","app.js","sha256.js","styles.css","fonts/anton-regular.ttf","icon.svg","icon.png","apple-touch-icon.png","favicon.ico","manifest.webmanifest").contains(name)) throw error(404,"not_found","页面不存在");
        try(InputStream input=assets.open(name)) {
            if(input==null) throw error(404,"not_found","页面不存在");
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))>=0) {if(out.size()+count>2*1024*1024)throw error(500,"asset_too_large","页面资源过大");out.write(buffer,0,count);}
            if(shortcut&&out.size()==0)throw error(404,"not_found","快捷指令尚未就绪");
            String mime=shortcut?"application/x-apple-shortcut":name.endsWith(".ttf")?"font/ttf":name.endsWith(".js")?"text/javascript; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".svg")?"image/svg+xml":name.endsWith(".png")?"image/png":name.endsWith(".webmanifest")?"application/manifest+json":"text/html; charset=utf-8";
            Map<String,String> headers=new LinkedHashMap<>();headers.put("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            if(shortcut)headers.put("Content-Disposition","attachment; filename=\"FileFlash.shortcut\"");
            if(request.method.equals("HEAD")) {response.begin(200,mime,out.size(),headers);response.flush();}
            else response.bytes(200,mime,out.toByteArray(),headers);
        } catch(FileNotFoundException e) {throw error(404,"not_found","页面不存在");}
    }

    private void checkHostAndOrigin(HttpServer.Request request) throws HttpError {
        String host=request.header("host");
        if(host==null || host.length()>128 || host.contains("@") || host.contains("/") || host.contains("\\")) throw error(400,"bad_host","无效的 Host");
        URI uri;
        try {uri=new URI("http://"+host);} catch(URISyntaxException e) {throw error(400,"bad_host","无效的 Host");}
        String hostname=uri.getHost();int port=uri.getPort()<0?80:uri.getPort();
        if(hostname==null || port!=http.port()) throw error(403,"host_rejected","请使用应用显示的连接地址");
        if(hostname.startsWith("[") && hostname.endsWith("]")) hostname=hostname.substring(1,hostname.length()-1);
        String local=request.socket.getLocalAddress().getHostAddress();
        if(!hostname.equalsIgnoreCase(local) && !(request.socket.getLocalAddress().isLoopbackAddress() && (hostname.equalsIgnoreCase("localhost")||hostname.equals("127.0.0.1")||hostname.equals("::1"))))
            throw error(403,"host_rejected","请使用应用显示的连接地址");
        String origin=request.header("origin");
        if(origin!=null && !origin.equalsIgnoreCase("http://"+host)) throw error(403,"origin_rejected","不允许跨站访问");
        if("cross-site".equalsIgnoreCase(request.header("sec-fetch-site"))) throw error(403,"origin_rejected","不允许跨站访问");
    }

    private synchronized TextMessage addText(String text,String sender) {
        if(text==null || text.isEmpty()) throw new IllegalArgumentException("文字不能为空");
        if(text.getBytes(StandardCharsets.UTF_8).length>512*1024) throw new IllegalArgumentException("文字最多 512 KiB");
        TextMessage message=new TextMessage();message.id=randomToken(18);message.text=text;message.sender=sender;message.createdAt=now();
        int total=text.getBytes(StandardCharsets.UTF_8).length;
        for(TextMessage existing:texts)total+=existing.text.getBytes(StandardCharsets.UTF_8).length;
        while(texts.size()>=100 || total>512*1024) {TextMessage removed=texts.removeFirst();total-=removed.text.getBytes(StandardCharsets.UTF_8).length;}
        texts.addLast(message);return message;
    }

    private synchronized void ratePair(String address) throws HttpError {
        long time=now();Rate rate=pairingRates.get(address);
        if(rate==null || time-rate.start>60_000) {
            if(pairingRates.size()>=128)pairingRates.remove(pairingRates.keySet().iterator().next());
            rate=new Rate();rate.start=time;pairingRates.put(address,rate);
        }
        if(++rate.count>20) throw error(429,"pairing_limit","配对请求过多，请一分钟后重试");
    }

    private void persist(Upload upload) throws IOException {
        File target=metaFile(upload),temp=new File(incoming,upload.id+".json.tmp");
        byte[] bytes=GSON.toJson(upload).getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream output=new FileOutputStream(temp)) {output.write(bytes);output.getFD().sync();}
        move(temp,target);
    }

    private void persist(Upload upload,TransportDiagnostics.Trace trace) throws IOException {
        trace.mark("metadata-syncing");long started=System.nanoTime();
        try {persist(upload);}finally{trace.addDuration("metadataSync",started);}
    }

    private void recover() throws IOException {
        File[] records=incoming.listFiles((dir,name)->name.endsWith(".json"));
        if(records==null)throw new IOException("无法读取接收缓存目录");
        for(File record:records) {
            if(!record.isFile()||Files.isSymbolicLink(record.toPath()))continue;
            if(record.length()>32*1024) {Files.deleteIfExists(record.toPath());continue;}
            try {
                // Close metadata before replacing it: Windows forbids renaming an open destination.
                Upload upload=GSON.fromJson(readMetadata(record),Upload.class);
                if(upload==null||!validId(upload.id)||!record.getName().equals(upload.id+".json")||!validId(upload.resumeKey)||upload.name==null||upload.size<0||upload.offset<0||upload.offset>upload.size||upload.createdAt<0||upload.updatedAt<0||!safeName(upload.name).equals(upload.name))throw new JsonParseException("Invalid upload metadata");
                if(upload.finalName!=null && !safeName(upload.finalName).equals(upload.finalName))throw new JsonParseException("Invalid received name");
                if(!Arrays.asList("uploading","failed","verifying","promoting","complete","cancelled","deleting","releasing","released").contains(upload.status))throw new JsonParseException("Invalid upload status");
                if(Files.isSymbolicLink(partFile(upload).toPath()))throw new JsonParseException("Invalid upload path");
                if(upload.finalName!=null)try {ownedFile(received,upload.finalName);}catch(IOException e){throw new JsonParseException("Invalid received path",e);}
                if(upload.restartOnly&&!isComplete(upload)&&!"releasing".equals(upload.status)) {
                    if(upload.finalName!=null)Files.deleteIfExists(ownedFile(received,upload.finalName).toPath());
                    Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(record.toPath());
                    Files.deleteIfExists(new File(incoming,upload.id+".json.tmp").toPath());continue;
                }
                if("deleting".equals(upload.status)||"releasing".equals(upload.status)) {
                    if(upload.finalName!=null)Files.deleteIfExists(ownedFile(received,upload.finalName).toPath());
                    Files.deleteIfExists(partFile(upload).toPath());
                    if("releasing".equals(upload.status)) {upload.status="released";upload.finalName=null;persist(upload);uploads.put(upload.id,upload);}
                    else Files.deleteIfExists(record.toPath());
                    continue;
                }
                if("released".equals(upload.status)) {
                    if(upload.offset!=upload.size||!isSha(upload.sha256)||upload.finalName!=null)throw new JsonParseException("Invalid completion receipt");
                    Files.deleteIfExists(partFile(upload).toPath());uploads.put(upload.id,upload);continue;
                }
                if("cancelled".equals(upload.status)) {
                    Files.deleteIfExists(partFile(upload).toPath());Files.deleteIfExists(record.toPath());continue;
                }
                if(upload.status.equals("promoting")) {
                    File destination=upload.finalName==null?null:new File(received,upload.finalName);
                    if(destination!=null && destination.isFile() && destination.length()==upload.size && hashFile(destination).equals(upload.sha256)) {
                        upload.status="complete";upload.name=upload.finalName;persist(upload);
                    } else {
                        // An unknown final file may be the sole surviving copy if metadata was
                        // damaged. Keep it protected instead of treating user bytes as garbage.
                        upload.status="uploading";upload.finalName=null;
                    }
                }
                if(upload.status.equals("complete")) {
                    File destination=upload.finalName==null?null:new File(received,upload.finalName);
                    if(destination!=null && destination.isFile() && destination.length()==upload.size) {uploads.put(upload.id,upload);addReceived(upload);}
                    else {Files.deleteIfExists(record.toPath());Files.deleteIfExists(partFile(upload).toPath());}
                    continue;
                }
                File part=partFile(upload);
                if(!part.isFile()) {upload.offset=0;upload.lastOffset=-1;upload.lastLength=0;upload.lastSha=null;}
                try(RandomAccessFile file=new RandomAccessFile(part,"rw")) {
                    if(file.length()<upload.offset) {upload.offset=0;upload.lastOffset=-1;upload.lastLength=0;upload.lastSha=null;}
                    file.setLength(upload.offset);file.getFD().sync();
                }
                upload.reservationOffset=upload.offset;
                upload.status="uploading";upload.error=null;persist(upload);uploads.put(upload.id,upload);
            } catch(JsonParseException|IllegalArgumentException|NullPointerException ignored) {
                // Never expose malformed persisted paths or mislabel corrupt metadata as complete.
                Files.deleteIfExists(record.toPath());
            }
        }
        Set<String> liveParts=new HashSet<>();
        for(Upload upload:uploads.values()) {
            if(isIncomplete(upload))liveParts.add(upload.id+".part");
        }
        // Recovery runs before accepting requests, so no active atomic-write temporary can be swept.
        File[] leftovers=incoming.listFiles();
        if(leftovers!=null)for(File leftover:leftovers) {
            String name=leftover.getName();
            if(leftover.isDirectory())continue;
            if(engineId(name,".json.tmp")!=null||(engineId(name,".part")!=null&&!liveParts.contains(name))||Files.isSymbolicLink(leftover.toPath()))Files.deleteIfExists(leftover.toPath());
        }
        pruneReceipts();
    }

    private void addReceived(Upload upload) {
        ShareFile file=new ShareFile();file.id=upload.id;file.name=upload.name;file.size=upload.size;file.mime=safeMime(upload.mime);
        file.sha256=upload.sha256;file.createdAt=upload.updatedAt;file.received=true;file.temporary=isTemporaryReceived(upload);
        File source=new File(received,upload.finalName);file.source=offset->openFile(source,offset);files.put(file.id,file);
    }
    private String uniqueName(String desired) {
        if(!new File(received,desired).exists())return desired;
        int dot=desired.lastIndexOf('.');String stem=dot>0?desired.substring(0,dot):desired,ext=dot>0?desired.substring(dot):"";
        for(int n=2;n<100000;n++) {String name=stem+" ("+n+")"+ext;if(!new File(received,name).exists())return name;}
        return randomToken(12)+"-"+desired;
    }
    private File partFile(Upload upload) {return new File(incoming,upload.id+".part");}
    private File metaFile(Upload upload) {return new File(incoming,upload.id+".json");}
    private static File ownedFile(File parent,String name) throws IOException {
        File file=new File(parent,name).getCanonicalFile();
        if(!file.getParentFile().equals(parent.getCanonicalFile()))throw new IOException("接收缓存路径无效");
        return file;
    }
    private static boolean isComplete(Upload upload) {return "complete".equals(upload.status)||"released".equals(upload.status);}
    private static boolean isIncomplete(Upload upload) {return "uploading".equals(upload.status)||"failed".equals(upload.status);}
    private static boolean isTemporaryReceived(Upload upload) {return "complete".equals(upload.status)&&upload.temporaryReceived&&!upload.saveRequested;}
    private static boolean isReclaimable(Upload upload) {return isIncomplete(upload)||isTemporaryReceived(upload);}
    private File cacheFile(Upload upload) {return isTemporaryReceived(upload)?new File(received,upload.finalName):partFile(upload);}
    private static String engineId(String name,String suffix) {if(!name.endsWith(suffix))return null;String id=name.substring(0,name.length()-suffix.length());return validId(id)?id:null;}
    private static long saturatedAdd(long a,long b) {return b>Long.MAX_VALUE-a?Long.MAX_VALUE:a+b;}
    private static String readMetadata(File file) throws IOException {
        // Use stream APIs available on Android 14; Files.readString is absent from its runtime.
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        try(InputStream input=new FileInputStream(file)) {
            int count;
            while((count=input.read(buffer))!=-1) {
                if(bytes.size()+count>32*1024)throw new IOException("上传元数据过大");
                bytes.write(buffer,0,count);
            }
        }
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
    private static void mkdir(File file) throws IOException {if(!file.isDirectory()&&!file.mkdirs())throw new IOException("无法创建接收目录");}
    private static void move(File from,File to) throws IOException {
        try {Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException e) {Files.move(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    private static InputStream openFile(File file,long offset) throws IOException {
        FileInputStream input=new FileInputStream(file);
        try {input.getChannel().position(offset);return input;}catch(IOException e){input.close();throw e;}
    }
    private static String hashFile(File file) throws IOException {
        return hashFile(file,()->true);
    }
    private static String hashFile(File file,java.util.function.BooleanSupplier allowed) throws IOException {
        MessageDigest digest=digest();byte[] buffer=new byte[128*1024];
        try(InputStream input=new FileInputStream(file)) {
            int count;while((count=input.read(buffer))>=0) {if(Thread.currentThread().isInterrupted()||!allowed.getAsBoolean())throw new InterruptedIOException("停止校验");digest.update(buffer,0,count);}
        }
        return hex(digest.digest());
    }
    private static MessageDigest digest() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static String hex(byte[] bytes) {StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static String randomToken(int bytes) {byte[] data=new byte[bytes];RANDOM.nextBytes(data);return Base64.getUrlEncoder().withoutPadding().encodeToString(data);}
    private static long now() {return System.currentTimeMillis();}
    private static boolean constantEquals(String a,String b) {return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static boolean valid(Session session) {return session!=null&&now()-session.lastSeen<SESSION_LIFETIME&&!session.revoked;}
    private Session sessionById(String id) {for(Session s:sessions.values())if(s.id.equals(id))return s;return null;}
    private static ClientInfo clientCopy(Session s) {ClientInfo c=new ClientInfo();c.id=s.id;c.name=s.name;c.connectedAt=s.connectedAt;c.lastSeen=s.lastSeen;return c;}
    private ShareFile getFile(String id) throws HttpError {ShareFile file=files.get(id);if(file==null)throw error(404,"file_not_found","文件已移除");return file;}
    private static boolean validId(String id) {return id!=null&&id.matches("[A-Za-z0-9_-]{16,64}");}
    private static String lastId(String path) throws HttpError {String id=path.substring(path.lastIndexOf('/')+1);if(!validId(id))throw error(404,"not_found","任务不存在");return id;}
    private static void requireMethod(HttpServer.Request r,String method) throws HttpError {if(!r.method.equals(method))throw error(405,"method_not_allowed","不支持此操作");}
    private static void requireDownloadMethod(HttpServer.Request r) throws HttpError {if(!r.method.equals("GET")&&!r.method.equals("HEAD"))throw error(405,"method_not_allowed","不支持此操作");}
    private static HttpError error(int status,String code,String message) {return new HttpError(status,code,message);}
    private static long headerLong(HttpServer.Request r,String header) throws HttpError {
        String value=r.header(header);if(value==null||!value.matches("[0-9]{1,19}"))throw error(400,"invalid_offset","缺少有效的分块位置");
        try{return Long.parseLong(value);}catch(NumberFormatException e){throw error(400,"invalid_offset","分块位置超出范围");}
    }
    private static JsonObject readJson(HttpServer.Request request,int maximum) throws IOException {
        String type=request.header("content-type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw error(400,"json_required","需要 JSON 请求内容");
        byte[] bytes=request.readBody(maximum);
        try {
            String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonElement element=GSON.fromJson(json,JsonElement.class);if(element==null||!element.isJsonObject())throw new JsonParseException("object required");return element.getAsJsonObject();
        } catch(JsonParseException|CharacterCodingException|IllegalStateException e){throw error(400,"invalid_json","请求内容格式无效");}
    }
    private static String string(JsonObject json,String key,boolean required) throws HttpError {
        JsonElement value=json.get(key);if(value==null||value.isJsonNull()){if(!required)return null;throw error(400,"missing_field","缺少参数："+key);}
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw error(400,"invalid_field","参数格式无效："+key);
        return value.getAsString();
    }
    private static long nonNegativeLong(JsonObject json,String key) throws HttpError {
        JsonElement value=json.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]{1,19}"))throw error(400,"invalid_size","文件大小无效");
        try{return Long.parseLong(value.getAsString());}catch(NumberFormatException e){throw error(400,"invalid_size","文件大小超出范围");}
    }
    private static boolean isSha(String sha) {return sha!=null&&sha.matches("[0-9a-fA-F]{64}");}
    private static String optionalSha(JsonObject json,String key) throws HttpError {String value=string(json,key,false);if(value==null||value.isEmpty())return null;if(!isSha(value))throw error(400,"invalid_checksum","SHA-256 格式无效");return value.toLowerCase(Locale.ROOT);}
    private static boolean optionalBoolean(JsonObject json,String key) throws HttpError {
        JsonElement value=json.get(key);if(value==null)return false;
        if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw error(400,"invalid_field","参数格式无效："+key);
        return value.getAsBoolean();
    }
    static String safeName(String original) {
        if(original==null)original="未命名文件";
        String normalized=Normalizer.normalize(original,Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}/\\\\:*?\"<>|]","_").trim();
        while(normalized.startsWith("."))normalized=normalized.substring(1);
        while(normalized.endsWith("."))normalized=normalized.substring(0,normalized.length()-1);
        if(normalized.isEmpty())normalized="未命名文件";
        // 180 UTF-8 bytes leaves room for collision suffixes on common 255-byte filesystems.
        while(normalized.getBytes(StandardCharsets.UTF_8).length>180)normalized=normalized.substring(0,normalized.offsetByCodePoints(0,normalized.codePointCount(0,normalized.length())-1));
        if(normalized.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?"))normalized="_"+normalized;
        return normalized;
    }
    private static String safeMime(String mime) {return mime!=null&&mime.matches("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")?mime:"application/octet-stream";}
    private void completeShortcutFilename(Upload upload) throws IOException {
        int dot=upload.name.lastIndexOf('.');
        boolean hasExtension=dot>0&&dot<upload.name.length()-1;
        boolean versionSuffix=hasExtension&&upload.name.substring(dot+1).matches("(?i)(?:v|ver)?[0-9]+");
        if(hasExtension&&!versionSuffix&&!"application/octet-stream".equalsIgnoreCase(upload.mime))return;
        File source=partFile(upload);
        byte[] prefix=new byte[(int)Math.min(source.length(),64L*1024)];int count=0;
        try(InputStream input=new FileInputStream(source)) {
            while(count<prefix.length) {int read=input.read(prefix,count,prefix.length-count);if(read<0)break;count+=read;}
        }
        MediaType type=mediaType(prefix,count);
        // Get Name removes only the real extension: trip.v2.jpg can arrive as trip.v2.
        // A verified signature disambiguates version-like suffixes, never an arbitrary custom suffix.
        if(type!=null&&versionSuffix)hasExtension=false;
        if(!hasExtension) {
            upload.name=fitShortcutName(upload.name,"."+(type==null?"bin":type.extension));
            upload.mime=type==null?"application/octet-stream":type.mime;
        } else if(type!=null)upload.mime=type.mime;
    }
    private static String fitShortcutName(String stem,String extension) {
        // Leave room for uniqueName's largest numeric collision suffix in persisted safe names.
        while((stem+extension).getBytes(StandardCharsets.UTF_8).length>172)
            stem=stem.substring(0,stem.offsetByCodePoints(0,stem.codePointCount(0,stem.length())-1));
        return stem+extension;
    }

    /** Magic identifies a container, not its decodability; unknown/oversized headers stay binary. */
    private static MediaType mediaType(byte[] bytes,int length) {
        // Image signatures follow https://mimesniff.spec.whatwg.org/#matching-an-image-type-pattern.
        if(matches(bytes,length,0,0xff,0xd8,0xff))return new MediaType("jpg","image/jpeg");
        if(matches(bytes,length,0,0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a))return new MediaType("png","image/png");
        if(ascii(bytes,length,0,"GIF87a")||ascii(bytes,length,0,"GIF89a"))return new MediaType("gif","image/gif");
        if(ascii(bytes,length,0,"RIFF")&&ascii(bytes,length,8,"WEBPVP"))return new MediaType("webp","image/webp");
        if(matches(bytes,length,0,0x49,0x49,0x2a,0)||matches(bytes,length,0,0x4d,0x4d,0,0x2a))return new MediaType("tif","image/tiff");
        if(ascii(bytes,length,0,"BM"))return new MediaType("bmp","image/bmp");
        // Parse complete, bounded top-level boxes. Never search arbitrary payload bytes for ftyp.
        int offset=0;
        while(offset<=length-8) {
            long size=unsignedInt(bytes,offset);int header=8;
            if(size==1) {
                if(offset>length-16||(bytes[offset+8]&0x80)!=0)return null;
                size=(unsignedInt(bytes,offset+8)<<32)|unsignedInt(bytes,offset+12);header=16;
            }
            if(size<header||size>length-offset)return null;
            int end=offset+(int)size;
            if(ascii(bytes,length,offset+4,"ftyp")) {
                int brands=offset+header;
                if(end-brands<8||(end-brands)%4!=0)return null;
                if(brand(bytes,brands,end,"avif","avis"))return new MediaType("avif","image/avif");
                if(brand(bytes,brands,end,"hevc","hevx","hevm","hevs"))return new MediaType("heics","image/heic-sequence");
                if(brand(bytes,brands,end,"heic","heix","heim","heis"))return new MediaType("heic","image/heic");
                if(brand(bytes,brands,end,"msf1"))return new MediaType("heifs","image/heif-sequence");
                if(brand(bytes,brands,end,"mif1"))return new MediaType("heif","image/heif");
                if(brand(bytes,brands,end,"qt  "))return new MediaType("mov","video/quicktime");
                if(brand(bytes,brands,end,"M4A ","M4B ","M4P "))return new MediaType("m4a","audio/mp4");
                if(brand(bytes,brands,end,"mp41","mp42","M4V ","M4VH","M4VP"))return new MediaType("mp4","video/mp4");
                return null;
            }
            offset=end;
        }
        return null;
    }
    private static long unsignedInt(byte[] bytes,int offset) {
        return ((long)(bytes[offset]&255)<<24)|((long)(bytes[offset+1]&255)<<16)|((long)(bytes[offset+2]&255)<<8)|(bytes[offset+3]&255);
    }
    private static boolean matches(byte[] bytes,int length,int offset,int... expected) {
        if(offset<0||offset>length-expected.length)return false;
        for(int i=0;i<expected.length;i++)if((bytes[offset+i]&255)!=expected[i])return false;
        return true;
    }
    private static boolean ascii(byte[] bytes,int length,int offset,String expected) {
        if(offset<0||offset>length-expected.length())return false;
        for(int i=0;i<expected.length();i++)if(bytes[offset+i]!=(byte)expected.charAt(i))return false;
        return true;
    }
    private static boolean brand(byte[] bytes,int start,int end,String... expected) {
        for(int offset=start;offset<end;offset+=offset==start?8:4)
            for(String value:expected)if(ascii(bytes,end,offset,value))return true;
        return false;
    }
    private static final class MediaType {
        final String extension,mime;
        MediaType(String extension,String mime){this.extension=extension;this.mime=mime;}
    }
    private static String shortName(String name) {if(name==null||name.trim().isEmpty())return "浏览器";String result=name.replaceAll("[\\p{Cntrl}]","").trim();return result.length()>60?result.substring(0,60):result;}
    private static String contentDisposition(String name) {
        String encoded;
        try {encoded=URLEncoder.encode(name,"UTF-8").replace("+","%20").replace("*","%2A");}catch(UnsupportedEncodingException e){throw new AssertionError(e);}
        int dot=name.lastIndexOf('.');String extension=dot>=0?name.substring(dot+1):"";
        String fallback="download"+(extension.matches("[A-Za-z0-9]{1,32}")?"."+extension:"");
        return "attachment; filename=\""+fallback+"\"; filename*=UTF-8''"+encoded;
    }
    private static JsonObject uploadJson(Upload upload,boolean key) {
        String status=upload.status;boolean lengthKnown=!upload.unknownLength;
        long offset=upload.offset,size=upload.size;
        JsonObject json=new JsonObject();json.addProperty("id",upload.id);json.addProperty("name",upload.name);json.addProperty("size",size);json.addProperty("mime",upload.mime);
        json.addProperty("offset",offset);json.addProperty("lengthKnown",lengthKnown);json.addProperty("status","released".equals(status)?"complete":status);json.addProperty("sha256",upload.sha256);json.addProperty("error",upload.error);
        json.addProperty("temporary",isTemporaryReceived(upload));
        json.addProperty("restartOnly",upload.restartOnly);
        json.addProperty("createdAt",upload.createdAt);json.addProperty("updatedAt",upload.updatedAt);if(key)json.addProperty("resumeKey",upload.resumeKey);return json;
    }
    private static final class Session {
        String id,name,secret;long connectedAt;volatile long lastSeen;volatile boolean revoked;long windowStart=now();int requests;
        final Set<Socket> connections=ConcurrentHashMap.newKeySet();
    }
    private static final class Rate {long start;int count;}
    private static final class Ticket {final String fileId,clientId;final long expiresAt;Ticket(String f,String c,long e){fileId=f;clientId=c;expiresAt=e;}}
    private static final class Upload {
        String id,name,mime,resumeKey,owner,finalName,lastSha;
        volatile String sha256,status,error;
        long createdAt,lastOffset=-1;volatile long size,offset,updatedAt;int lastLength;
        // Missing fields in legacy metadata deliberately keep its old completed private files safe.
        volatile boolean temporaryReceived,saveRequested;
        boolean restartOnly;
        transient volatile boolean cancelRequested;
        transient volatile boolean unknownLength;
        // Actual synchronized native bytes can outlive a rolled-back protocol offset until retry.
        // Decreases and truncation share the budget guard; increases follow successful data sync.
        transient volatile long reservationOffset;
        transient volatile Socket activeSocket;
        transient final Object lifecycle=new Object();
        transient volatile int activeRequests;
        transient volatile boolean maintenance;
    }
    private static final class TextMessage {String id,text,sender;long createdAt;}
    private static final class Download {
        String id,fileId,name,clientId;long size,startedAt;volatile long transferred;volatile String status,error;transient volatile boolean cancelled;
        transient volatile Socket socket;
    }
}
