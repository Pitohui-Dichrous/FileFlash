package app.fileflash.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ExplicitCleanupTest {
    @TempDir Path directory;
    TransferServer server;
    Path storage;
    String base,session;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach void start()throws Exception {
        storage=directory.resolve("private");open();
    }
    void open()throws Exception {
        server=new TransferServer(storage.toFile(),path->new ByteArrayInputStream(new byte[0]));
        server.start(0);base="http://127.0.0.1:"+server.getPort();
        HttpResponse<byte[]> paired=send("POST","/api/pair","{\"token\":\""+server.getPairToken()+"\"}",false);
        assertEquals(201,paired.statusCode());session=json(paired).get("session").getAsString();
    }
    @AfterEach void stop(){server.stop();}
    HttpResponse<byte[]> send(String method,String path,String body,boolean auth)throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(4));
        if(auth)request.header("Authorization","Bearer "+session);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static JsonObject json(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    JsonObject state(){return JsonParser.parseString(server.state()).getAsJsonObject();}
    JsonObject allocate(String name,int size,boolean stream)throws Exception {
        HttpResponse<byte[]> response=send("POST","/api/uploads","{\"name\":\""+name+"\",\"size\":"+size+",\"restartOnly\":"+stream+"}",true);
        assertEquals(201,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return json(response);
    }
    String receive(String name,byte[] bytes)throws Exception {
        JsonObject upload=allocate(name,bytes.length,true);String id=upload.get("id").getAsString();
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/stream"))
                .header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return id;
    }
    void assertPrivateEmpty()throws IOException {
        for(String name:Arrays.asList("uploads","received"))try(java.util.stream.Stream<Path> entries=Files.list(storage.resolve(name))){assertEquals(0,entries.count(),name);}
    }

    @Test void clearsAllHistoryAndPrivateCopiesButPreservesDownloadsOriginalSharesTextsAndPairing()throws Exception {
        byte[] savedBytes={1,2,3,4},originalBytes={9,8,7};
        Path publicFile=directory.resolve("Download/FileFlash/saved.jpg");Files.createDirectories(publicFile.getParent());Files.write(publicFile,savedBytes);
        Path original=directory.resolve("original.txt");Files.write(original,originalBytes);
        ShareFile originalShare=server.share(original.toFile());
        String saved=receive("saved.jpg",savedBytes);
        server.releaseReceivedCache(saved,offset->Files.newInputStream(publicFile));
        String failedSave=receive("retry-save.jpg",new byte[]{4,5,6});
        try(TransferServer.ReceivedSave claim=server.claimReceivedSave(failedSave)){assertTrue(claim.file().isFile());}
        allocate("unfinished.bin",8192,false);
        Files.write(storage.resolve("received/orphan.jpg"),new byte[]{7,7});
        Files.write(storage.resolve("uploads/abandoned.json.tmp"),new byte[]{8});
        server.sendText("保留文字");
        assertEquals(200,send("GET","/api/files/"+saved,null,true).statusCode());
        String ticket=json(send("POST","/api/files/"+originalShare.id+"/ticket",null,true)).get("url").getAsString();
        String token=server.getPairToken();

        assertTrue(server.clearTransferHistory(3000)>=6);
        assertPrivateEmpty();
        JsonObject after=state();
        assertEquals(0,after.getAsJsonArray("uploads").size());assertEquals(0,after.getAsJsonArray("downloads").size());
        assertEquals(1,after.getAsJsonArray("files").size());assertEquals(originalShare.id,after.getAsJsonArray("files").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(1,after.getAsJsonArray("texts").size());assertEquals(1,after.getAsJsonArray("clients").size());
        assertEquals(token,server.getPairToken());assertTrue(server.isRunning());
        assertArrayEquals(savedBytes,Files.readAllBytes(publicFile));assertArrayEquals(originalBytes,Files.readAllBytes(original));
        assertEquals(200,send("GET","/api/state",null,true).statusCode());
        assertEquals(401,send("GET",ticket,null,false).statusCode());
        assertArrayEquals(originalBytes,send("GET","/api/files/"+originalShare.id,null,true).body());

        server.stop();open();
        assertEquals(0,state().getAsJsonArray("uploads").size());assertEquals(0,state().getAsJsonArray("files").size());
        assertArrayEquals(savedBytes,Files.readAllBytes(publicFile));
    }

    @Test void timeoutDoesNotDeletePinnedSaveAndRetryUsesTheSamePairing()throws Exception {
        byte[] bytes={4,2,6};String id=receive("held.jpg",bytes);
        TransferServer.ReceivedSave claim=server.claimReceivedSave(id);
        try {
            IOException failure=assertThrows(IOException.class,()->server.clearTransferHistory(50));
            assertTrue(failure.getMessage().contains("仍在使用"));
            assertArrayEquals(bytes,Files.readAllBytes(claim.file().toPath()));
            assertNotNull(server.receivedFile(id));
            assertFalse(Files.exists(storage.resolve("clear-transfer-history.pending")));
        } finally {claim.close();claim.close();}
        server.clearTransferHistory(2000);assertPrivateEmpty();
        assertEquals(0,state().getAsJsonArray("uploads").size());
        receive("next.jpg",bytes);
    }

    @Test void blocksNewTransfersAndRestoreWhileAThumbnailDrainsButAllowsState()throws Exception {
        String id=receive("held.jpg",new byte[]{1,2});
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        server.setThumbnailProvider(file->{entered.countDown();await(release);return new byte[]{(byte)255,(byte)216,(byte)255,(byte)217};});
        CompletableFuture<byte[]> preview=CompletableFuture.supplyAsync(()->{try{return server.thumbnail(id);}catch(IOException e){throw new CompletionException(e);}});
        assertTrue(entered.await(2,TimeUnit.SECONDS));
        CompletableFuture<Long> cleanup=CompletableFuture.supplyAsync(()->{try{return server.clearTransferHistory(3000);}catch(IOException e){throw new CompletionException(e);}});
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);int result;
            do {result=send("POST","/api/uploads","{\"name\":\"next.bin\",\"size\":0}",true).statusCode();if(result!=503)Thread.sleep(5);}while(result!=503&&System.nanoTime()<deadline);
            assertEquals(503,result);
            assertThrows(IllegalStateException.class,()->server.restoreReceivedShare("restoredSavedFile","saved.jpg",1,"image/jpeg",null,0,offset->new ByteArrayInputStream(new byte[1])));
            assertEquals(200,send("GET","/api/state",null,true).statusCode());
            assertNotNull(server.receivedFile(id));assertFalse(cleanup.isDone());
        } finally {release.countDown();}
        assertNotNull(preview.get(2,TimeUnit.SECONDS));cleanup.get(2,TimeUnit.SECONDS);
        assertPrivateEmpty();assertEquals(0,state().getAsJsonArray("uploads").size());
        receive("next.jpg",new byte[]{2,3});
    }

    @Test void cancelsAnActiveStreamingBodyBeforeDeletingItsPrivateFiles()throws Exception {
        JsonObject upload=allocate("sending.bin",8*1024*1024,true);String id=upload.get("id").getAsString();
        try(Socket sender=new Socket("127.0.0.1",server.getPort())) {
            String header="POST /api/uploads/"+id+"/stream HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nX-Upload-Key: "+upload.get("resumeKey").getAsString()+"\r\nContent-Length: "+(8*1024*1024)+"\r\n\r\n";
            sender.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));sender.getOutputStream().write(new byte[4096]);sender.getOutputStream().flush();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);int active;
            do {active=json(send("GET","/api/diagnostics",null,true)).getAsJsonObject("engine").get("uploadsActive").getAsInt();if(active==0)Thread.sleep(5);}while(active==0&&System.nanoTime()<deadline);
            assertEquals(1,active);
            server.clearTransferHistory(3000);assertPrivateEmpty();
            assertEquals(0,state().getAsJsonArray("uploads").size());
            assertEquals(404,send("GET","/api/uploads/"+id,null,true).statusCode());
            assertEquals(200,send("GET","/api/state",null,true).statusCode());
        }
        server.stop();open();assertPrivateEmpty();assertEquals(0,state().getAsJsonArray("uploads").size());
    }

    @Test void cancelsAnActiveDownloadWithoutTouchingItsOriginalSource()throws Exception {
        CountDownLatch opened=new CountDownLatch(1);AtomicBoolean closed=new AtomicBoolean();
        ShareFile original=server.share("original.bin",64*1024*1024,"application/octet-stream",offset->{opened.countDown();return new InputStream(){
            @Override public int read(){return 1;}
            @Override public int read(byte[] bytes,int start,int length){Arrays.fill(bytes,start,start+length,(byte)1);return length;}
            @Override public void close(){closed.set(true);}
        };});
        try(Socket receiver=new Socket()) {
            receiver.setReceiveBufferSize(1024);receiver.connect(new InetSocketAddress("127.0.0.1",server.getPort()));
            String request="GET /api/files/"+original.id+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\n\r\n";
            receiver.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));receiver.getOutputStream().flush();
            assertTrue(opened.await(2,TimeUnit.SECONDS));
            server.clearTransferHistory(3000);
            assertTrue(closed.get());assertEquals(0,state().getAsJsonArray("downloads").size());
            assertEquals(original.id,state().getAsJsonArray("files").get(0).getAsJsonObject().get("id").getAsString());
        }
    }

    @Test void aRequestAcceptedBeforeCleanupCannotCreateHistoryAfterTheGateReopens()throws Exception {
        try(Socket sender=new Socket("127.0.0.1",server.getPort())) {
            sender.setSoTimeout(3000);
            sender.getOutputStream().write("POST /api/uploads HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII));sender.getOutputStream().flush();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);int pending;
            do {pending=json(send("GET","/api/diagnostics",null,true)).getAsJsonObject("transport").get("pendingHeaders").getAsInt();if(pending==0)Thread.sleep(5);}while(pending==0&&System.nanoTime()<deadline);
            assertTrue(pending>0);server.clearTransferHistory(1000);
            byte[] body="{\"name\":\"stale.bin\",\"size\":0}".getBytes(StandardCharsets.US_ASCII);
            String rest="Host: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\n\r\n";
            sender.getOutputStream().write(rest.getBytes(StandardCharsets.US_ASCII));sender.getOutputStream().write(body);sender.getOutputStream().flush();
            String response=new String(sender.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 409"),response);assertTrue(response.contains("transfer_cleared"));
            assertEquals(0,state().getAsJsonArray("uploads").size());assertPrivateEmpty();
        }
        receive("fresh.jpg",new byte[]{1,2});
    }

    @Test void recoversInterruptedExplicitClearWithoutResurrectingSavedOrLegacyHistory()throws Exception {
        String id=receive("legacy.jpg",new byte[]{1,2,3});
        try(TransferServer.ReceivedSave ignored=server.claimReceivedSave(id)){}
        allocate("unfinished.bin",12,false);
        Path publicFile=directory.resolve("Download/FileFlash/legacy.jpg");Files.createDirectories(publicFile.getParent());Files.write(publicFile,new byte[]{1,2,3});
        server.stop();
        Path metadata=storage.resolve("uploads/"+id+".json");JsonObject legacy=JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();legacy.remove("temporaryReceived");Files.writeString(metadata,legacy.toString());
        Files.write(storage.resolve("clear-transfer-history.pending"),new byte[]{1});
        open();
        assertPrivateEmpty();assertEquals(0,state().getAsJsonArray("uploads").size());assertEquals(0,state().getAsJsonArray("files").size());
        assertFalse(Files.exists(storage.resolve("clear-transfer-history.pending")));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(publicFile));
    }

    @Test void failedDeletionKeepsNewTransfersBlockedUntilCleanupIsRetried()throws Exception {
        receive("temporary.jpg",new byte[]{1});
        Path unexpected=Files.createDirectory(storage.resolve("received/unexpected-directory"));
        assertThrows(IOException.class,()->server.clearTransferHistory(2000));
        assertTrue(Files.exists(storage.resolve("clear-transfer-history.pending")));
        assertEquals(503,send("POST","/api/uploads","{\"name\":\"new.bin\",\"size\":0}",true).statusCode());
        assertEquals(200,send("GET","/api/state",null,true).statusCode());
        Files.delete(unexpected);server.clearTransferHistory(2000);assertPrivateEmpty();
        receive("next.jpg",new byte[]{2});
    }

    private static void await(CountDownLatch latch)throws IOException {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Test latch timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
