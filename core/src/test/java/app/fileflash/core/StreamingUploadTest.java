package app.fileflash.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class StreamingUploadTest {
    @TempDir Path directory;
    TransferServer server;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String base,session;

    @BeforeEach void open()throws Exception {
        server=new TransferServer(directory.toFile(),name->new ByteArrayInputStream(new byte[0]));server.start(0);base="http://127.0.0.1:"+server.getPort();session=pair();
    }
    @AfterEach void close(){server.stop();}
    String pair()throws Exception {return object(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),null,null)).get("session").getAsString();}
    static JsonObject object(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    static String sha(byte[] bytes)throws Exception{return TransferServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    String path(JsonObject task){return "/api/uploads/"+task.get("id").getAsString();}
    Path part(JsonObject task){return directory.resolve("uploads").resolve(task.get("id").getAsString()+".part");}
    Path meta(JsonObject task){return directory.resolve("uploads").resolve(task.get("id").getAsString()+".json");}
    HttpResponse<byte[]> send(String method,String path,byte[] bytes,String auth,String key)throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json");
        if(auth!=null)request.header("Authorization","Bearer "+auth);if(key!=null)request.header("X-Upload-Key",key);
        return client.send(request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonObject create(int size,boolean restartOnly)throws Exception {
        String json="{\"name\":\"stream.bin\",\"size\":"+size+",\"restartOnly\":"+restartOnly+"}";
        HttpResponse<byte[]> response=send("POST","/api/uploads",json.getBytes(StandardCharsets.UTF_8),session,null);assertEquals(201,response.statusCode());return object(response);
    }
    HttpResponse<byte[]> stream(JsonObject task,byte[] bytes)throws Exception {return send("POST",path(task)+"/stream",bytes,session,task.get("resumeKey").getAsString());}
    void absent(JsonObject task)throws Exception {
        assertEquals(404,send("GET",path(task),null,session,task.get("resumeKey").getAsString()).statusCode());assertFalse(Files.exists(part(task)));assertFalse(Files.exists(meta(task)));assertNull(server.receivedFile(task.get("id").getAsString()));
    }
    Socket socket()throws Exception {Socket socket=new Socket("127.0.0.1",server.getPort());socket.setSoTimeout(3000);return socket;}
    String header(JsonObject task,Integer length,boolean expect) {
        return "POST "+path(task)+"/stream HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+
                "\r\nX-Upload-Key: "+task.get("resumeKey").getAsString()+"\r\n"+(length==null?"":"Content-Length: "+length+"\r\n")+(expect?"Expect: 100-continue\r\n":"")+"\r\n";
    }
    String readHeader(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int ending=0;
        while(ending<4){int next=input.read();assertTrue(next>=0);bytes.write(next);int wanted=ending==0||ending==2?'\r':'\n';ending=next==wanted?ending+1:next=='\r'?1:0;assertTrue(bytes.size()<16384);}return bytes.toString(StandardCharsets.ISO_8859_1);
    }
    void waitForProgress(JsonObject task)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        do {for(JsonElement value:object(send("GET","/api/state",null,session,null)).getAsJsonArray("uploads")){JsonObject current=value.getAsJsonObject();if(current.get("id").getAsString().equals(task.get("id").getAsString())&&current.get("offset").getAsLong()>0)return;}Thread.sleep(10);}while(System.nanoTime()<deadline);
        fail("The stream must start writing before the whole body is available");
    }

    @Test void streamsLargeBodyWithoutClientHashAndRemainsPrivateUntilExplicitSave()throws Exception {
        byte[] data=new byte[10*1024*1024+37];new Random(42).nextBytes(data);JsonObject task=create(data.length,true);
        HttpResponse<byte[]> response=stream(task,data);assertEquals(200,response.statusCode());JsonObject receipt=object(response);
        assertEquals("complete",receipt.get("status").getAsString());assertEquals(data.length,receipt.get("offset").getAsLong());assertEquals(sha(data),receipt.get("sha256").getAsString());assertTrue(receipt.get("restartOnly").getAsBoolean());assertTrue(receipt.get("temporary").getAsBoolean());
        assertArrayEquals(data,Files.readAllBytes(server.receivedFile(task.get("id").getAsString()).toPath()));assertEquals(0,server.protectedReceivedBytes());assertEquals(data.length,server.reclaimableCacheBytes());
        String other=pair();assertArrayEquals(data,send("GET","/api/files/"+task.get("id").getAsString(),null,other,null).body());
        try(Socket duplicate=socket()) {
            duplicate.getOutputStream().write(header(task,data.length,true).getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHeader(duplicate.getInputStream()).startsWith("HTTP/1.1 409"),"A duplicate full stream cannot overwrite its completion or trigger 100 Continue");
            assertEquals("upload_closed",JsonParser.parseString(new String(duplicate.getInputStream().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject().get("code").getAsString());
        }
        assertNotNull(server.receivedFile(task.get("id").getAsString()));
        assertEquals(200,send("DELETE",path(task),null,session,null).statusCode(),"An uncertain ACK may be discarded while its completed file is still temporary");absent(task);
    }

    @Test void lengthMismatchAndMissingLengthFailBeforeContinueAndRemoveRestartTask()throws Exception {
        for(Integer length:Arrays.asList(null,2,4)) {
            JsonObject task=create(3,true);
            try(Socket socket=socket()) {socket.getOutputStream().write(header(task,length,true).getBytes(StandardCharsets.US_ASCII));String response=readHeader(socket.getInputStream());assertTrue(response.startsWith(length==null?"HTTP/1.1 400":"HTTP/1.1 409"),response);}
            absent(task);
        }
        JsonObject zero=create(0,true);
        try(Socket socket=socket()){socket.getOutputStream().write(header(zero,null,false).getBytes(StandardCharsets.US_ASCII));assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 400"));}
        absent(zero);
    }

    @Test void eofDeletesReceivedPrefixMetadataAndShare()throws Exception {
        JsonObject task=create(4*1024*1024,true);
        try(Socket socket=socket()) {OutputStream out=socket.getOutputStream();out.write(header(task,4*1024*1024,false).getBytes(StandardCharsets.US_ASCII));out.write(new byte[8193]);out.flush();socket.shutdownOutput();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 400"));}
        absent(task);assertEquals(0,object(send("GET","/api/state",null,session,null)).getAsJsonArray("files").size());
    }

    @Test void activeStreamDoesNotBlockOtherCreateStateOrDiagnosticsAndCancelInterruptsIt()throws Exception {
        JsonObject task=create(4*1024*1024,true);String other=pair();
        try(Socket socket=socket()) {
            OutputStream out=socket.getOutputStream();out.write(header(task,4*1024*1024,false).getBytes(StandardCharsets.US_ASCII));out.write(new byte[128*1024+17]);out.flush();waitForProgress(task);
            long started=System.nanoTime();assertEquals(200,send("GET","/api/state",null,other,null).statusCode());assertEquals(200,send("GET","/api/diagnostics",null,other,null).statusCode());
            byte[] creation=("{\"name\":\"parallel.bin\",\"size\":1,\"sha256\":\""+sha(new byte[]{1})+"\"}").getBytes(StandardCharsets.UTF_8);
            HttpResponse<byte[]> created=send("POST","/api/uploads",creation,other,null);assertEquals(201,created.statusCode());
            assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos(),"A partial stream must not monopolize creation or status locks");
            started=System.nanoTime();int status=send("DELETE",path(task),null,session,null).statusCode();assertTrue(status==200||status==404);assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos());absent(task);
        }
    }

    @Test void stopDeletesActiveAndUnstartedStreamsButPreservesNativeCheckpoint()throws Exception {
        JsonObject nativeTask=create(4,false),pending=create(9,true),active=create(4*1024*1024,true);byte[] prefix={1,2,3};
        HttpRequest chunk=HttpRequest.newBuilder(URI.create(base+path(nativeTask))).header("Authorization","Bearer "+session).header("X-Offset","0").header("X-Chunk-SHA256",sha(prefix)).PUT(HttpRequest.BodyPublishers.ofByteArray(prefix)).build();assertEquals(200,client.send(chunk,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        try(Socket socket=socket()) {OutputStream out=socket.getOutputStream();out.write(header(active,4*1024*1024,false).getBytes(StandardCharsets.US_ASCII));out.write(new byte[8193]);out.flush();waitForProgress(active);server.stop();}
        assertFalse(Files.exists(part(pending)));assertFalse(Files.exists(meta(pending)));assertFalse(Files.exists(part(active)));assertFalse(Files.exists(meta(active)));assertArrayEquals(prefix,Files.readAllBytes(part(nativeTask)));
        open();assertEquals(3,object(send("GET",path(nativeTask),null,session,nativeTask.get("resumeKey").getAsString())).get("offset").getAsLong());absent(pending);absent(active);
    }

    @Test void recoveryDropsInterruptedStreamAndPromotionButRestoresCompletedRelay()throws Exception {
        JsonObject interrupted=create(123,true),complete=create(3,true);byte[] metadata=Files.readAllBytes(meta(interrupted));assertEquals(200,stream(complete,new byte[]{3,4,5}).statusCode());
        server.stop();Files.write(meta(interrupted),metadata);Files.write(part(interrupted),new byte[91]);
        JsonObject promotion=JsonParser.parseString(new String(metadata,StandardCharsets.UTF_8)).getAsJsonObject();String id=UUID.randomUUID().toString();promotion.addProperty("id",id);promotion.addProperty("status","promoting");promotion.addProperty("offset",123);promotion.addProperty("finalName","interrupted-promoting.bin");promotion.addProperty("sha256",sha(new byte[123]));
        Path promoted=directory.resolve("received").resolve("interrupted-promoting.bin");Files.write(promoted,new byte[123]);Files.writeString(directory.resolve("uploads").resolve(id+".json"),promotion.toString());
        open();absent(interrupted);assertFalse(Files.exists(promoted));assertFalse(Files.exists(directory.resolve("uploads").resolve(id+".json")));
        assertArrayEquals(new byte[]{3,4,5},Files.readAllBytes(server.receivedFile(complete.get("id").getAsString()).toPath()));assertEquals("complete",object(send("GET",path(complete),null,session,complete.get("resumeKey").getAsString())).get("status").getAsString());
    }

    @Test void authRecoveryKeyAndModeGuardsDoNotEraseProtectedFiles()throws Exception {
        JsonObject task=create(3,true);String other=pair();byte[] bytes={4,5,6};
        assertEquals(401,send("POST",path(task)+"/stream",bytes,null,null).statusCode());assertEquals(403,send("POST",path(task)+"/stream",bytes,other,null).statusCode());assertTrue(Files.exists(meta(task)));
        assertEquals(200,send("POST",path(task)+"/stream",bytes,other,task.get("resumeKey").getAsString()).statusCode());
        try(TransferServer.ReceivedSave save=server.claimReceivedSave(task.get("id").getAsString())) {assertArrayEquals(bytes,Files.readAllBytes(save.file().toPath()));}
        assertEquals(409,send("DELETE",path(task),null,session,null).statusCode());assertEquals(409,stream(task,bytes).statusCode());assertNotNull(server.receivedFile(task.get("id").getAsString()));
        JsonObject legacy=create(3,false);assertEquals(409,stream(legacy,bytes).statusCode());assertTrue(Files.exists(meta(legacy)));assertTrue(Files.exists(part(legacy)));
    }

    @Test void emptyStreamAndStrictRestartFlagNeedNoClientChecksum()throws Exception {
        JsonObject task=create(0,true);HttpResponse<byte[]> empty=stream(task,new byte[0]);assertEquals(200,empty.statusCode());assertEquals(sha(new byte[0]),object(empty).get("sha256").getAsString());
        assertEquals(400,send("POST","/api/uploads","{\"name\":\"bad.bin\",\"size\":0,\"restartOnly\":\"true\"}".getBytes(StandardCharsets.UTF_8),session,null).statusCode());
        JsonObject body=new JsonObject();body.addProperty("name","ignored-client-checksum.bin");body.addProperty("size",3);body.addProperty("restartOnly",true);body.addProperty("sha256","0".repeat(64));JsonObject taskWithOldHash=object(send("POST","/api/uploads",body.toString().getBytes(StandardCharsets.UTF_8),session,null));
        HttpResponse<byte[]> response=stream(taskWithOldHash,new byte[]{7,8,9});assertEquals(200,response.statusCode());assertEquals(sha(new byte[]{7,8,9}),object(response).get("sha256").getAsString());
    }
}
