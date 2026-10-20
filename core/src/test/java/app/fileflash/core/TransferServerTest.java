package app.fileflash.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TransferServerTest {
    @TempDir Path directory;
    TransferServer server;
    HttpClient client=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    String base,session,clientId;

    @BeforeEach void start() throws Exception {open();pair();}
    void open() throws Exception {
        server=new TransferServer(directory.toFile(),path->new ByteArrayInputStream("<!doctype html>".getBytes(StandardCharsets.UTF_8)));
        server.start(0);base="http://127.0.0.1:"+server.getPort();
    }
    void pair() throws Exception {
        HttpResponse<byte[]> response=json("POST","/api/pair","{\"token\":\""+server.getPairToken()+"\",\"name\":\"iPhone 测试\"}",false);
        assertEquals(201,response.statusCode());JsonObject result=object(response);session=result.get("session").getAsString();clientId=result.get("clientId").getAsString();
    }
    @AfterEach void close() {server.stop();}

    HttpResponse<byte[]> json(String method,String path,String body,boolean auth) throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+path));
        if(auth)builder.header("Authorization","Bearer "+session);
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    HttpResponse<byte[]> chunk(JsonObject upload,long offset,byte[] data,String sha) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+upload.get("id").getAsString()))
                .header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .header("X-Offset",Long.toString(offset)).header("X-Chunk-SHA256",sha)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonObject create(String name,long size,String sha) throws Exception {
        JsonObject body=new JsonObject();body.addProperty("name",name);body.addProperty("size",size);body.addProperty("mime","application/octet-stream");if(sha!=null)body.addProperty("sha256",sha);
        HttpResponse<byte[]> response=json("POST","/api/uploads",body.toString(),true);assertEquals(201,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return object(response);
    }
    static JsonObject object(HttpResponse<byte[]> response) {return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    static String sha(byte[] data)throws Exception {return TransferServer.hex(MessageDigest.getInstance("SHA-256").digest(data));}

    @Test void authenticatesAndGuardsOriginsAndPaths() throws Exception {
        assertEquals(401,json("GET","/api/state",null,false).statusCode());
        assertEquals(401,json("POST","/api/pair","{\"token\":\"bad\"}",false).statusCode());
        assertEquals(200,json("GET","/",null,false).statusCode());
        assertEquals(200,json("GET","/sha256.js",null,false).statusCode());
        assertEquals(400,json("GET","/%2e%2e/private",null,false).statusCode());
        HttpResponse<byte[]> response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/state")).header("Authorization","Bearer "+session).header("Origin","https://evil.invalid").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(403,response.statusCode());
        server.revokeClient(clientId);
        assertEquals(401,json("GET","/api/state",null,true).statusCode());
    }

    @Test void disconnectReturnsSuccessThenInvalidatesSession()throws Exception {
        assertEquals(200,json("DELETE","/api/session",null,true).statusCode());
        assertEquals(401,json("GET","/api/state",null,true).statusCode());
    }

    @Test void chunkChecksDuplicateRetryCompletionAndRange() throws Exception {
        byte[] data=new byte[CHUNK_PLUS()];new Random(82).nextBytes(data);String checksum=sha(data);
        JsonObject upload=create("../../演示😀.zip",data.length,checksum);String id=upload.get("id").getAsString();
        byte[] first=Arrays.copyOf(data,TransferServer.CHUNK_SIZE);
        assertEquals(422,chunk(upload,0,first,"0".repeat(64)).statusCode());
        assertEquals(0,object(json("GET","/api/uploads/"+id,null,true)).get("offset").getAsLong());
        assertEquals(200,chunk(upload,0,first,sha(first)).statusCode());
        assertEquals(200,chunk(upload,0,first,sha(first)).statusCode());
        assertEquals(409,chunk(upload,1,new byte[]{1},sha(new byte[]{1})).statusCode());
        assertEquals(409,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());
        byte[] tail=Arrays.copyOfRange(data,first.length,data.length);
        assertEquals(200,chunk(upload,first.length,tail,sha(tail)).statusCode());
        HttpResponse<byte[]> completed=json("POST","/api/uploads/"+id+"/complete","{}",true);
        assertEquals(200,completed.statusCode());assertEquals("complete",object(completed).get("status").getAsString());
        assertArrayEquals(data,Files.readAllBytes(server.receivedFile(id).toPath()));
        assertFalse(server.receivedFile(id).getName().contains("/"));
        HttpResponse<byte[]> download=client.send(HttpRequest.newBuilder(URI.create(base+"/api/files/"+id)).header("Authorization","Bearer "+session).header("Range","bytes=2097150-2097160").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,download.statusCode());assertArrayEquals(Arrays.copyOfRange(data,2097150,2097161),download.body());
        String ticket=object(json("POST","/api/files/"+id+"/ticket",null,true)).get("url").getAsString();
        assertArrayEquals(data,json("GET",ticket,null,false).body());
        server.revokeClient(clientId);assertEquals(401,json("GET",ticket,null,false).statusCode());
    }
    private static int CHUNK_PLUS() {return TransferServer.CHUNK_SIZE+37;}

    @Test void durableResumeTruncatesUncommittedTailAndRejectsMissingRecoveryKey() throws Exception {
        byte[] first="第一块\n😀".getBytes(StandardCharsets.UTF_8),last="tail".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream all=new ByteArrayOutputStream();all.write(first);all.write(last);
        JsonObject upload=create("resume.bin",first.length+last.length,sha(all.toByteArray()));String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,first,sha(first)).statusCode());String oldPair=server.getPairToken();
        server.stop();
        Files.write(directory.resolve("uploads").resolve(id+".part"),new byte[]{44,55,66},StandardOpenOption.APPEND);
        open();assertNotEquals(oldPair,server.getPairToken());pair();
        assertEquals(first.length,Files.size(directory.resolve("uploads").resolve(id+".part")));
        assertEquals(403,json("GET","/api/uploads/"+id,null,true).statusCode());
        HttpResponse<byte[]> status=client.send(HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id)).header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString()).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(first.length,object(status).get("offset").getAsLong());
        assertEquals(200,chunk(upload,first.length,last,sha(last)).statusCode());
        HttpResponse<byte[]> complete=client.send(HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/complete")).header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,complete.statusCode());assertArrayEquals(all.toByteArray(),Files.readAllBytes(server.receivedFile(id).toPath()));
        // Exercise both incomplete and complete persisted-metadata loading, including the recovered share.
        server.stop();open();pair();
        assertNotNull(server.receivedFile(id));assertArrayEquals(all.toByteArray(),Files.readAllBytes(server.receivedFile(id).toPath()));
        assertArrayEquals(all.toByteArray(),json("GET","/api/files/"+id,null,true).body());
    }

    @Test void wrongWholeFileDigestResetsToZeroAndNeverCompletes() throws Exception {
        byte[] data="correct bytes".getBytes(StandardCharsets.UTF_8);JsonObject upload=create("test.bin",data.length,"0".repeat(64));String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,data,sha(data)).statusCode());
        assertEquals(422,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());
        JsonObject state=object(json("GET","/api/uploads/"+id,null,true));assertEquals(0,state.get("offset").getAsLong());assertEquals("failed",state.get("status").getAsString());assertNull(server.receivedFile(id));
    }

    @Test void handlesZeroByteFilesAndNamesWithoutOverwriting() throws Exception {
        for(int n=0;n<2;n++) {
            JsonObject upload=create("same.txt",0,sha(new byte[0]));String id=upload.get("id").getAsString();
            assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());assertEquals(n==0?"same.txt":"same (2).txt",server.receivedFile(id).getName());
            assertEquals(0,json("GET","/api/files/"+id,null,true).body().length);
        }
        assertEquals("_CON.txt",TransferServer.safeName("CON.txt"));
    }

    @Test void rangesBeyondFourGiBAreLongAndBounded() throws Exception {
        long size=(1L<<32)+65537,begin=(1L<<32)+1234;
        ShareFile file=server.share("large.bin",size,"application/octet-stream",offset->new InputStream(){long position=offset;@Override public int read(){return position<size?(int)(position++&255):-1;}@Override public int read(byte[] b,int off,int count){int n=(int)Math.min(count,size-position);if(n<=0)return -1;for(int i=0;i<n;i++)b[off+i]=(byte)(position++&255);return n;}});
        HttpResponse<byte[]> response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/files/"+file.id)).header("Authorization","Bearer "+session).header("Range","bytes="+begin+"-"+(begin+31)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206,response.statusCode());assertEquals("bytes "+begin+"-"+(begin+31)+"/"+size,response.headers().firstValue("content-range").orElseThrow());
        assertEquals(32,response.body().length);for(int i=0;i<32;i++)assertEquals((byte)((begin+i)&255),response.body()[i]);
        assertArrayEquals(new long[]{size-20,20},TransferServer.parseRange("bytes=-20",size));
        assertNull(TransferServer.parseRange("bytes=0-1,2-3",size));assertNull(TransferServer.parseRange("bytes=9223372036854775808-",size));
    }

    @Test void cleanupOnlyDeletesPrivateReceivedCopyAndItsMetadata()throws Exception {
        Path external=directory.resolve("outside-host-share.bin");Files.write(external,new byte[]{4,5,6});ShareFile hostShare=server.share(external.toFile());
        byte[] data={1,2,3};JsonObject upload=create("private-cache.bin",data.length,sha(data));String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,data,sha(data)).statusCode());assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());
        File cached=server.receivedFile(id);assertNotNull(cached);
        assertThrows(IOException.class,()->server.removeReceivedFile(hostShare.id));assertTrue(Files.exists(external));
        server.removeReceivedFile(id);assertNull(server.receivedFile(id));assertFalse(cached.exists());assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".json")));
        assertEquals(404,json("GET","/api/files/"+id,null,true).statusCode());assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(external));
        server.stop();open();pair();assertNull(server.receivedFile(id));assertTrue(Files.exists(external));
    }

    @Test void preservesUnicodeTextAndRejectsInvalidSizesAndCancelledUploads() throws Exception {
        String text="中文 English 😀\n<>&\\\"";JsonObject body=new JsonObject();body.addProperty("text",text);
        assertEquals(201,json("POST","/api/texts",body.toString(),true).statusCode());
        assertEquals(text,object(json("GET","/api/state",null,true)).getAsJsonArray("texts").get(0).getAsJsonObject().get("text").getAsString());
        assertEquals(400,json("POST","/api/uploads","{\"name\":\"bad\",\"size\":-1}",true).statusCode());
        assertEquals(400,json("POST","/api/texts","{\"text\":\"\"}",true).statusCode());
        JsonObject upload=create("cancel.bin",1,null);String id=upload.get("id").getAsString();
        assertEquals(200,json("DELETE","/api/uploads/"+id,null,true).statusCode());assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".part")));
        assertEquals(409,chunk(upload,0,new byte[]{1},sha(new byte[]{1})).statusCode());
        assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".json")));
    }

    @Test void releasedCacheKeepsShareAndDurableCompletionReceiptWithoutPrivateBytes() throws Exception {
        byte[] data="published bytes 😀".getBytes(StandardCharsets.UTF_8);String checksum=sha(data);
        JsonObject upload=create("published.bin",data.length,checksum);String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,data,checksum).statusCode());
        assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());
        Path published=directory.resolve("user-owned.bin");Files.write(published,data);File cached=server.receivedFile(id);
        Source source=offset->{FileInputStream input=new FileInputStream(published.toFile());input.getChannel().position(offset);return input;};
        server.releaseReceivedCache(id,source);assertFalse(cached.exists());assertNull(server.receivedFile(id));
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
        JsonObject receipt=JsonParser.parseString(Files.readString(directory.resolve("uploads").resolve(id+".json"))).getAsJsonObject();
        assertEquals("released",receipt.get("status").getAsString());assertFalse(receipt.has("finalName"));
        assertEquals("complete",object(json("POST","/api/uploads/"+id+"/complete","{}",true)).get("status").getAsString());
        server.stop();open();pair();
        assertEquals(0,Objects.requireNonNull(directory.resolve("uploads").toFile().listFiles((parent,name)->name.endsWith(".part"))).length);
        ShareFile restored=server.restoreReceivedShare(id,"published.bin",data.length,"application/octet-stream",checksum,System.currentTimeMillis(),source);
        assertTrue(restored.received);assertEquals(checksum,restored.sha256);assertEquals(id,restored.id);
        HttpResponse<byte[]> retry=client.send(HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/complete"))
                .header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,retry.statusCode());assertEquals("complete",object(retry).get("status").getAsString());
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
        assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertTrue(Files.exists(published));
    }

    @Test void idleCleanupExpiresPartsPreservesRecentResumeGraceAndExplicitlyRetainedData() throws Exception {
        byte[] data={1,2,3};String checksum=sha(data);
        JsonObject old=create("old.bin",6,null),recent=create("recent.bin",6,null),aged=create("aged.bin",6,null),complete=create("keep.bin",3,checksum);
        for(JsonObject upload:List.of(old,recent,aged,complete))assertEquals(200,chunk(upload,0,data,checksum).statusCode());
        String oldId=old.get("id").getAsString(),recentId=recent.get("id").getAsString(),agedId=aged.get("id").getAsString(),completeId=complete.get("id").getAsString();
        assertEquals(200,json("POST","/api/uploads/"+completeId+"/complete","{}",true).statusCode());
        try(TransferServer.ReceivedSave ignored=server.claimReceivedSave(completeId)) { /* Explicit save intent protects a retry. */ }
        server.stop();long time=System.currentTimeMillis();
        setUpdatedAt(oldId,time-25L*60*60_000);setUpdatedAt(agedId,time-31L*60_000);setUpdatedAt(recentId,time-5L*60_000);
        open();pair();assertEquals(9,server.reclaimableCacheBytes());
        assertEquals(6,server.cleanupStaleUploads(24L*60*60_000,0,time));
        assertFalse(Files.exists(directory.resolve("uploads").resolve(oldId+".part")));assertFalse(Files.exists(directory.resolve("uploads").resolve(agedId+".part")));
        assertTrue(Files.exists(directory.resolve("uploads").resolve(recentId+".part")));assertNotNull(server.receivedFile(completeId));
        assertEquals(404,json("GET","/api/uploads/"+oldId,null,true).statusCode());
        assertEquals(3,server.cleanupStaleUploads(0,0,time));assertNotNull(server.receivedFile(completeId));
    }

    @Test void releasingCacheRefusesActiveDownloadAndPreservesItsSourceUntilFinished() throws Exception {
        byte[] data={4,5,6};String checksum=sha(data);JsonObject upload=create("busy.bin",3,checksum);String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,data,checksum).statusCode());assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());File cached=server.receivedFile(id);
        CountDownLatch opened=new CountDownLatch(1),unblock=new CountDownLatch(1);
        Source slow=offset->{opened.countDown();return new ByteArrayInputStream(data){@Override public synchronized int read(byte[] buffer,int at,int length){try{if(!unblock.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}return super.read(buffer,at,length);}};};
        ShareFile file=server.restoreReceivedShare(id,"busy.bin",3,"application/octet-stream",checksum,System.currentTimeMillis(),slow);
        Source replacement=offset->new ByteArrayInputStream(Arrays.copyOfRange(data,(int)offset,data.length));
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> active=client.sendAsync(HttpRequest.newBuilder(URI.create(base+"/api/files/"+id)).header("Authorization","Bearer "+session).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        try {
            assertTrue(opened.await(5,TimeUnit.SECONDS));assertThrows(IOException.class,()->server.releaseReceivedCache(id,replacement));
            assertSame(slow,file.source);assertTrue(cached.exists());assertEquals(checksum,file.sha256);
        } finally {unblock.countDown();}
        assertArrayEquals(data,active.get(5,TimeUnit.SECONDS).body());
        server.releaseReceivedCache(id,replacement);assertSame(replacement,file.source);assertFalse(cached.exists());
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
    }

    @Test void releasingRemovedShareDeletesDuplicateWithoutRevealingSavedFile() throws Exception {
        byte[] data={5,6,7};String checksum=sha(data);JsonObject upload=create("hidden.bin",3,checksum);String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,data,checksum).statusCode());assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());File cached=server.receivedFile(id);
        Path published=directory.resolve("saved-hidden.bin");Files.write(published,data);
        Source replacement=offset->{FileInputStream input=new FileInputStream(published.toFile());input.getChannel().position(offset);return input;};
        server.removeFile(id);server.releaseReceivedCache(id,replacement);server.releaseReceivedCache(id,replacement);
        assertFalse(cached.exists());assertTrue(Files.exists(published));assertEquals(404,json("GET","/api/files/"+id,null,true).statusCode());
        assertEquals("complete",object(json("GET","/api/uploads/"+id,null,true)).get("status").getAsString());
        server.stop();open();pair();assertEquals(404,json("GET","/api/files/"+id,null,true).statusCode());assertTrue(Files.exists(published));
        HttpResponse<byte[]> retry=client.send(HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/complete"))
                .header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,retry.statusCode());assertNull(server.receivedFile(id));
    }

    @Test void idleCleanupSkipsActiveSlowChunkAndCanClearItAfterDisconnect() throws Exception {
        byte[] first={1,2,3};JsonObject upload=create("slow.bin",10,null);String id=upload.get("id").getAsString();
        assertEquals(200,chunk(upload,0,first,sha(first)).statusCode());
        try(Socket socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(5000);
            String request="PUT /api/uploads/"+id+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nContent-Length: 7\r\nX-Offset: 3\r\nX-Chunk-SHA256: "+sha(new byte[7])+"\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(0);socket.getOutputStream().flush();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(server.reclaimableCacheBytes()!=0&&System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals(0,server.reclaimableCacheBytes());
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis())));
            assertEquals(3,Files.size(directory.resolve("uploads").resolve(id+".part")));
            socket.shutdownOutput();assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine().startsWith("HTTP/1.1 400"));
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(server.reclaimableCacheBytes()!=3&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals(3,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));
        assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".json")));assertEquals(404,json("GET","/api/uploads/"+id,null,true).statusCode());
    }

    @Test void startupRemovesOrphansAndInvalidMetadataOnlyInsideEngineDirectories() throws Exception {
        JsonObject keep=create("keep-resume.bin",10,null);String keepId=keep.get("id").getAsString();byte[] data={7,8};assertEquals(200,chunk(keep,0,data,sha(data)).statusCode());
        server.stop();Path incoming=directory.resolve("uploads"),received=directory.resolve("received"),outside=directory.resolve("user-file.bin");
        Files.write(outside,new byte[]{99});Files.write(incoming.resolve("orphan_upload_id.part"),new byte[]{1});Files.write(incoming.resolve("leftover_upload_id.json.tmp"),new byte[]{2});
        Files.writeString(incoming.resolve("malformed.json"),"{");Files.write(received.resolve("orphan-private.bin"),new byte[]{3});
        open();pair();assertEquals(2,Files.size(incoming.resolve(keepId+".part")));assertTrue(Files.exists(outside));
        assertFalse(Files.exists(incoming.resolve("orphan_upload_id.part")));assertFalse(Files.exists(incoming.resolve("leftover_upload_id.json.tmp")));
        assertFalse(Files.exists(incoming.resolve("malformed.json")));assertTrue(Files.exists(received.resolve("orphan-private.bin")));
        assertEquals(1,server.protectedReceivedBytes());assertEquals(2,server.reclaimableCacheBytes());
    }

    @Test void runtimeSweepRemovesOnlyUnregisteredEngineUploadNames() throws Exception {
        Path incoming=directory.resolve("uploads");Files.write(incoming.resolve("orphan_upload_id.part"),new byte[]{1,2});Files.write(incoming.resolve("orphan_upload_id.json.tmp"),new byte[]{3});
        Files.write(incoming.resolve("unmanaged.part"),new byte[]{4});
        assertEquals(3,server.cleanupStaleUploads(24L*60*60_000,256L*1024*1024,System.currentTimeMillis()));
        assertFalse(Files.exists(incoming.resolve("orphan_upload_id.part")));assertFalse(Files.exists(incoming.resolve("orphan_upload_id.json.tmp")));assertTrue(Files.exists(incoming.resolve("unmanaged.part")));
    }

    @Test void startupFinishesInterruptedReleaseAndBoundsCompletionReceipts() throws Exception {
        JsonObject upload=create("release-crash.bin",0,sha(new byte[0]));String id=upload.get("id").getAsString();
        assertEquals(200,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());File cached=server.receivedFile(id);server.stop();
        Path record=directory.resolve("uploads").resolve(id+".json");JsonObject metadata=JsonParser.parseString(Files.readString(record)).getAsJsonObject();metadata.addProperty("status","releasing");Files.writeString(record,metadata.toString());
        open();pair();assertFalse(cached.exists());assertNull(server.receivedFile(id));assertEquals("complete",JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("uploads").get(0).getAsJsonObject().get("status").getAsString());
        server.stop();JsonObject receipt=JsonParser.parseString(Files.readString(record)).getAsJsonObject();
        for(int n=0;n<260;n++) {String copyId=String.format(Locale.ROOT,"%018d",n);receipt.addProperty("id",copyId);receipt.addProperty("updatedAt",System.currentTimeMillis()+n+1);Files.writeString(directory.resolve("uploads").resolve(copyId+".json"),receipt.toString());}
        open();pair();assertEquals(256,Objects.requireNonNull(directory.resolve("uploads").toFile().listFiles((parent,name)->name.endsWith(".json"))).length);
        assertEquals(0,Objects.requireNonNull(directory.resolve("uploads").toFile().listFiles((parent,name)->name.endsWith(".part"))).length);
    }

    void setUpdatedAt(String id,long time) throws Exception {
        Path path=directory.resolve("uploads").resolve(id+".json");JsonObject metadata=JsonParser.parseString(Files.readString(path)).getAsJsonObject();metadata.addProperty("updatedAt",time);Files.writeString(path,metadata.toString());
    }

    JsonObject completeRelay(String name,byte[] data) throws Exception {
        JsonObject upload=create(name,data.length,sha(data));
        for(int offset=0;offset<data.length;) {
            int end=Math.min(data.length,offset+TransferServer.CHUNK_SIZE);byte[] block=Arrays.copyOfRange(data,offset,end);
            assertEquals(200,chunk(upload,offset,block,sha(block)).statusCode());offset=end;
        }
        assertEquals(200,json("POST","/api/uploads/"+upload.get("id").getAsString()+"/complete","{}",true).statusCode());return upload;
    }

    JsonObject metadata(String id) throws Exception {return JsonParser.parseString(Files.readString(directory.resolve("uploads").resolve(id+".json"))).getAsJsonObject();}
    void awaitReclaimable(long bytes) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(server.reclaimableCacheBytes()!=bytes&&System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(bytes,server.reclaimableCacheBytes());
    }

    @Test void temporaryRelayCanBeDownloadedByAnotherPairedClientAndSurvivesRestart() throws Exception {
        byte[] data="B sends through A to C 😀".getBytes(StandardCharsets.UTF_8);
        JsonObject upload=completeRelay("relay.apk",data);String id=upload.get("id").getAsString();
        JsonObject initial=JsonParser.parseString(server.state()).getAsJsonObject();
        assertTrue(initial.getAsJsonArray("files").get(0).getAsJsonObject().get("temporary").getAsBoolean());
        assertTrue(initial.getAsJsonArray("uploads").get(0).getAsJsonObject().get("temporary").getAsBoolean());
        awaitReclaimable(data.length);assertEquals(0,server.protectedReceivedBytes());
        server.revokeClient(clientId);pair(); // C has its own session and no B recovery key.
        assertEquals(403,json("GET","/api/uploads/"+id,null,true).statusCode());
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
        server.stop();open();pair();
        assertTrue(JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject().get("temporary").getAsBoolean());
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
        awaitReclaimable(data.length);
    }

    @Test void sharedIdleBudgetEvictsOldPartialAndTemporaryCompleteWithoutTouchingRecentRelay() throws Exception {
        byte[] data={1,2,3};JsonObject partial=create("partial.bin",6,null);
        assertEquals(200,chunk(partial,0,data,sha(data)).statusCode());
        String partialId=partial.get("id").getAsString(),oldId=completeRelay("old-relay.bin",data).get("id").getAsString(),recentId=completeRelay("recent-relay.bin",data).get("id").getAsString();
        String ticket=object(json("POST","/api/files/"+oldId+"/ticket",null,true)).get("url").getAsString();
        server.stop();long time=System.currentTimeMillis();setUpdatedAt(partialId,time-32L*60_000);setUpdatedAt(oldId,time-31L*60_000);setUpdatedAt(recentId,time-5L*60_000);
        open();pair();assertEquals(9,server.reclaimableCacheBytes());assertEquals(6,server.cleanupStaleUploads(24L*60*60_000,3,time));
        assertEquals(404,json("GET","/api/files/"+oldId,null,true).statusCode());
        assertEquals(401,json("GET",ticket,null,false).statusCode());
        assertFalse(Files.exists(directory.resolve("uploads").resolve(oldId+".json")));assertFalse(Files.exists(directory.resolve("received").resolve("old-relay.bin")));
        assertArrayEquals(data,json("GET","/api/files/"+recentId,null,true).body());awaitReclaimable(3);
    }

    @Test void statePollingTicketsAndHeadDoNotKeepExpiredRelayAlive() throws Exception {
        byte[] data={9,8,7};String id=completeRelay("expire.bin",data).get("id").getAsString();
        server.stop();long time=System.currentTimeMillis(),old=time-25L*60*60_000;setUpdatedAt(id,old);open();pair();
        assertEquals(200,json("GET","/api/state",null,true).statusCode());
        assertEquals(200,json("HEAD","/api/files/"+id,null,true).statusCode());
        assertEquals(200,json("POST","/api/files/"+id+"/ticket",null,true).statusCode());
        assertEquals(old,metadata(id).get("updatedAt").getAsLong());
        assertEquals(data.length,server.cleanupStaleUploads(24L*60*60_000,256L*1024*1024,time));assertNull(server.receivedFile(id));
        assertTrue(JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("files").isEmpty());
    }

    @Test void actualDownloadRenewsRelayIdleLifetimeDurably() throws Exception {
        byte[] data={9,8,7};String id=completeRelay("renew.bin",data).get("id").getAsString();
        server.stop();long time=System.currentTimeMillis(),old=time-25L*60*60_000;setUpdatedAt(id,old);open();pair();
        assertArrayEquals(data,json("GET","/api/files/"+id,null,true).body());
        assertTrue(metadata(id).get("updatedAt").getAsLong()>=time);
        server.stop();open();pair();assertEquals(0,server.cleanupStaleUploads(24L*60*60_000,256L*1024*1024,System.currentTimeMillis()));assertNotNull(server.receivedFile(id));
    }

    @Test void activeRelayDownloadPinsBeforeSourceOpenAndRenewsAfterFinishing() throws Exception {
        byte[] data={4,5,6};String checksum=sha(data),id=completeRelay("busy-relay.bin",data).get("id").getAsString();
        CountDownLatch opened=new CountDownLatch(1),unblock=new CountDownLatch(1);
        Source slow=offset->{opened.countDown();try{if(!unblock.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException failure){throw new InterruptedIOException();}return new ByteArrayInputStream(Arrays.copyOfRange(data,(int)offset,data.length));};
        server.restoreReceivedShare(id,"busy-relay.bin",3,"application/octet-stream",checksum,System.currentTimeMillis(),slow);
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> active=client.sendAsync(HttpRequest.newBuilder(URI.create(base+"/api/files/"+id)).header("Authorization","Bearer "+session).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        long beforeFinish;
        try {
            assertTrue(opened.await(5,TimeUnit.SECONDS));assertEquals(0,server.reclaimableCacheBytes());
            assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()+25L*60*60_000));assertNotNull(server.receivedFile(id));
            assertThrows(IOException.class,()->server.removeReceivedFile(id));beforeFinish=System.currentTimeMillis();
        } finally {unblock.countDown();}
        assertArrayEquals(data,active.get(5,TimeUnit.SECONDS).body());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(server.reclaimableCacheBytes()!=3&&System.nanoTime()<deadline)Thread.sleep(5);
        assertTrue(metadata(id).get("updatedAt").getAsLong()>=beforeFinish);
        assertEquals(3,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertNull(server.receivedFile(id));
    }

    @Test void explicitSaveClaimPinsAndFailedSaveIntentSurvivesRestartUntilDiscard() throws Exception {
        byte[] data={2,3,4};String id=completeRelay("save-retry.bin",data).get("id").getAsString();
        TransferServer.ReceivedSave claim=server.claimReceivedSave(id);
        assertArrayEquals(data,Files.readAllBytes(claim.file().toPath()));assertTrue(metadata(id).get("saveRequested").getAsBoolean());
        assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()+25L*60*60_000));assertThrows(IOException.class,()->server.removeReceivedFile(id));
        claim.close();claim.close();assertEquals(0,server.reclaimableCacheBytes());assertEquals(data.length,server.protectedReceivedBytes());
        server.stop();open();pair();assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()+25L*60*60_000));
        assertFalse(JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject().get("temporary").getAsBoolean());
        try(TransferServer.ReceivedSave retry=server.claimReceivedSave(id)) {assertArrayEquals(data,Files.readAllBytes(retry.file().toPath()));}
        server.removeReceivedFile(id);assertNull(server.receivedFile(id));assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".json")));
    }

    @Test void legacyCompletedMetadataRemainsProtectedWhenTemporaryFlagWasAbsent() throws Exception {
        byte[] data={6,7,8};String id=completeRelay("legacy.bin",data).get("id").getAsString();server.stop();
        JsonObject old=metadata(id);old.remove("temporaryReceived");old.remove("saveRequested");old.addProperty("updatedAt",0);Files.writeString(directory.resolve("uploads").resolve(id+".json"),old.toString());
        open();pair();assertEquals(0,server.reclaimableCacheBytes());assertEquals(data.length,server.protectedReceivedBytes());
        assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertNotNull(server.receivedFile(id));
        assertFalse(JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("files").get(0).getAsJsonObject().get("temporary").getAsBoolean());
    }

    @Test void discardingTemporaryRelayRemovesItsShareTicketAndCompletionReceipt() throws Exception {
        byte[] data={7,8,9};JsonObject upload=completeRelay("discard-relay.bin",data);String id=upload.get("id").getAsString();
        String ticket=object(json("POST","/api/files/"+id+"/ticket",null,true)).get("url").getAsString();awaitReclaimable(data.length);server.removeReceivedFile(id);
        assertEquals(404,json("GET","/api/files/"+id,null,true).statusCode());assertEquals(401,json("GET",ticket,null,false).statusCode());
        assertEquals(404,json("POST","/api/uploads/"+id+"/complete","{}",true).statusCode());server.stop();open();pair();
        assertNull(server.receivedFile(id));assertEquals(0,server.reclaimableCacheBytes());assertEquals(0,server.protectedReceivedBytes());
    }

    @Test void sixIdlePreconnectionsCannotStarveStateOrUploadWorkers() throws Exception {
        assertHeaderWaitersDoNotStarve(false);
    }

    @Test void sixFragmentedHeadersCannotStarveStateOrUploadWorkers() throws Exception {
        assertHeaderWaitersDoNotStarve(true);
    }

    void assertHeaderWaitersDoNotStarve(boolean partial) throws Exception {
        byte[] block=new byte[TransferServer.CHUNK_SIZE];new Random(341).nextBytes(block);
        JsonObject upload=create("ios-stall.apk",2L*block.length,null);List<Socket> waiting=new ArrayList<>();
        try {
            for(int i=0;i<6;i++) {
                Socket socket=new Socket("127.0.0.1",server.getPort());waiting.add(socket);
                if(partial) {socket.getOutputStream().write("GET /api/state HTTP/1.1\r\nHost: 127.0.0.1\r\nX-Unfinished: ".getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();}
            }
            Thread.sleep(50); // Ensure the earlier sockets reach the listener before the real request.
            HttpRequest state=HttpRequest.newBuilder(URI.create(base+"/api/state")).timeout(java.time.Duration.ofSeconds(2)).header("Authorization","Bearer "+session).GET().build();
            assertEquals(200,client.send(state,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
            long started=System.nanoTime();
            assertEquals(200,chunk(upload,0,block,sha(block)).statusCode());
            assertEquals(200,chunk(upload,block.length,block,sha(block)).statusCode());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<2000,"Idle headers must not delay acknowledged chunks");
            assertEquals(2L*block.length,object(json("GET","/api/uploads/"+upload.get("id").getAsString(),null,true)).get("offset").getAsLong());
        } finally {for(Socket socket:waiting)socket.close();}
    }

    @Test void headerGateLimitsPendingMemoryAndRecoversAfterIdlePeersLeave() throws Exception {
        List<Socket> waiting=new ArrayList<>();
        try {
            for(int i=0;i<HttpServer.MAX_PENDING_HEADERS;i++)waiting.add(new Socket("127.0.0.1",server.getPort()));
            Thread.sleep(100);
            try(Socket excess=new Socket("127.0.0.1",server.getPort())) {
                excess.setSoTimeout(2000);assertEquals(-1,excess.getInputStream().read(),"Pending header capacity must be bounded");
            }
        } finally {for(Socket socket:waiting)socket.close();}
        assertEquals(200,json("GET","/api/state",null,true).statusCode());
    }

    @Test void partialHeaderHasAnAbsoluteDeadlineAndPrefetchedBinaryBodyIsUnchanged() throws Exception {
        try(Socket partial=new Socket("127.0.0.1",server.getPort())) {
            partial.setSoTimeout(7000);partial.getOutputStream().write('G');partial.getOutputStream().flush();
            long started=System.nanoTime();assertEquals(-1,partial.getInputStream().read());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<6500,"An unfinished header must expire");
        }
        byte[] data={0,'\r','\n','\r','\n',0,(byte)255,3};JsonObject upload=create("binary.bin",data.length,null);
        try(Socket socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(2000);
            ByteArrayOutputStream request=new ByteArrayOutputStream();request.write(rawChunkHeader(upload,0,data).getBytes(StandardCharsets.US_ASCII));request.write(data);
            socket.getOutputStream().write(request.toByteArray());socket.getOutputStream().flush();
            String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertTrue(status.startsWith("HTTP/1.1 200"));
        }
        assertArrayEquals(data,Files.readAllBytes(directory.resolve("uploads").resolve(upload.get("id").getAsString()+".part")));
    }

    String rawChunkHeader(JsonObject upload,long offset,byte[] data) throws Exception {
        return "PUT /api/uploads/"+upload.get("id").getAsString()+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+
                "\r\nX-Upload-Key: "+upload.get("resumeKey").getAsString()+"\r\nContent-Length: "+data.length+"\r\nX-Offset: "+offset+"\r\nX-Chunk-SHA256: "+sha(data)+"\r\n\r\n";
    }

    HttpResponse<byte[]> prefix(JsonObject upload,boolean key) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+upload.get("id").getAsString()+"/prefix")).timeout(java.time.Duration.ofSeconds(5)).header("Authorization","Bearer "+session);
        if(key)request.header("X-Upload-Key",upload.get("resumeKey").getAsString());
        return client.send(request.GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void prefixChecksOnlyCommittedBytesAndSurvivesLostAcknowledgementAndRestart() throws Exception {
        byte[] first=new byte[TransferServer.CHUNK_SIZE],tail={8,9,10};new Random(76).nextBytes(first);
        ByteArrayOutputStream complete=new ByteArrayOutputStream();complete.write(first);complete.write(tail);
        JsonObject upload=create("resume-prefix.apk",first.length+tail.length,null);String id=upload.get("id").getAsString();
        JsonObject empty=object(prefix(upload,true));assertEquals(sha(new byte[0]),empty.get("prefixSha256").getAsString());assertEquals(0,empty.get("offset").getAsLong());
        assertEquals(id,empty.get("id").getAsString());assertEquals(first.length+tail.length,empty.get("size").getAsLong());
        // The client drops the response instead of accepting its JSON acknowledgement.
        try(Socket socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(2000);socket.getOutputStream().write(rawChunkHeader(upload,0,first).getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(first);socket.getOutputStream().flush();
            assertEquals('H',socket.getInputStream().read());
        }
        Files.write(directory.resolve("uploads").resolve(id+".part"),new byte[]{99,98},StandardOpenOption.APPEND);
        JsonObject checkpoint=object(prefix(upload,true));assertEquals(first.length,checkpoint.get("offset").getAsLong());assertEquals(sha(first),checkpoint.get("prefixSha256").getAsString());
        server.stop();open();pair();
        assertEquals(403,prefix(upload,false).statusCode());assertEquals(sha(first),object(prefix(upload,true)).get("prefixSha256").getAsString());
        assertEquals(401,json("GET","/api/uploads/"+id+"/prefix",null,false).statusCode());
        assertEquals(200,chunk(upload,first.length,tail,sha(tail)).statusCode());
        HttpRequest finish=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/complete")).header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"sha256\":\""+sha(complete.toByteArray())+"\"}")).build();
        assertEquals(200,client.send(finish,HttpResponse.BodyHandlers.ofByteArray()).statusCode());assertEquals(409,prefix(upload,true).statusCode());
        assertArrayEquals(complete.toByteArray(),Files.readAllBytes(server.receivedFile(id).toPath()));
    }

    @Test void prefixCannotReadCancelledOrExpiredDataAndDoesNotExposeSecrets() throws Exception {
        JsonObject cancelled=create("cancel-prefix.bin",3,null);String id=cancelled.get("id").getAsString();
        assertEquals(405,json("POST","/api/uploads/"+id+"/prefix","{}",true).statusCode());
        assertFalse(object(prefix(cancelled,true)).has("resumeKey"));
        assertEquals(200,json("DELETE","/api/uploads/"+id,null,true).statusCode());assertEquals(409,prefix(cancelled,true).statusCode());
        JsonObject stale=create("expired-prefix.bin",3,null);assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertEquals(404,prefix(stale,true).statusCode());
    }

    @Test void parserRejectsAmbiguousLengthsAndUnsafeHost() throws Exception {
        assertEquals(400,raw("POST /api/pair HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nContent-Length: 0\r\nContent-Length: 0\r\n\r\n"));
        assertEquals(400,raw("POST /api/pair HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nTransfer-Encoding: chunked\r\n\r\n"));
        assertEquals(403,raw("GET / HTTP/1.1\r\nHost: evil.invalid:"+server.getPort()+"\r\n\r\n"));
        assertEquals(431,raw("GET / HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nX-Long: "+"a".repeat(17000)+"\r\n\r\n"));
        assertEquals(400,raw("GET / HTTP/1.1\nHost: 127.0.0.1\n\n"));
        assertEquals(400,json("POST","/api/pair","{token:'not-json'}",false).statusCode());
    }

    @Test void interruptedChunkNeverAdvancesCommittedOffset()throws Exception {
        JsonObject upload=create("interrupted.bin",10,null);String id=upload.get("id").getAsString();
        try(Socket socket=new Socket("127.0.0.1",server.getPort())) {
            String request="PUT /api/uploads/"+id+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nContent-Length: 10\r\nX-Offset: 0\r\nX-Chunk-SHA256: "+sha(new byte[10])+"\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(new byte[5]);socket.shutdownOutput();
            String status=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();assertTrue(status.startsWith("HTTP/1.1 400"));
        }
        assertEquals(0,object(json("GET","/api/uploads/"+id,null,true)).get("offset").getAsLong());
        assertEquals(0,Files.size(directory.resolve("uploads").resolve(id+".part")));
    }

    @Test void revokingClientInterruptsAnActiveStreamingDownload()throws Exception {
        CountDownLatch unblock=new CountDownLatch(1);long size=64L*1024*1024;
        ShareFile file=server.share("stream.bin",size,"application/octet-stream",offset->new InputStream(){int calls;long position=offset;@Override public int read(){return 0;}@Override public int read(byte[] b,int off,int length)throws IOException{if(calls++>0)try{if(!unblock.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new InterruptedIOException();}int n=(int)Math.min(length,size-position);if(n==0)return -1;position+=n;return n;}});
        try {
            HttpResponse<InputStream> response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/files/"+file.id)).header("Authorization","Bearer "+session).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());assertEquals(0,response.body().read());server.revokeClient(clientId);unblock.countDown();
            assertThrows(IOException.class,()->response.body().readAllBytes());response.body().close();
            JsonArray tasks=JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("downloads");assertEquals("failed",tasks.get(0).getAsJsonObject().get("status").getAsString());
        }finally{unblock.countDown();}
    }
    int raw(String request)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",server.getPort())) {
            socket.setSoTimeout(5000);socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();
            String line=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.ISO_8859_1)).readLine();
            assertNotNull(line);return Integer.parseInt(line.split(" ")[1]);
        }
    }
}
