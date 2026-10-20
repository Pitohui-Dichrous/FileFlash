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

class TransportDiagnosticsTest {
    @TempDir Path directory;
    TransferServer server;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String base,session;

    @BeforeEach void start()throws Exception {
        server=new TransferServer(directory.toFile(),name->new ByteArrayInputStream(new byte[0]));server.start(0);
        base="http://127.0.0.1:"+server.getPort();
        JsonObject pair=body(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),false));
        session=pair.get("session").getAsString();
    }
    @AfterEach void close(){server.stop();}

    HttpResponse<byte[]> send(String method,String path,byte[] data,boolean auth)throws Exception {
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(3)).header("Content-Type","application/json");
        if(auth)builder.header("Authorization","Bearer "+session);
        return client.send(builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static JsonObject body(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    JsonObject snapshot()throws Exception {HttpResponse<byte[]> response=send("GET","/api/diagnostics",null,true);assertEquals(200,response.statusCode());return body(response);}
    JsonObject trace(String id)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        do {
        for(JsonElement item:snapshot().getAsJsonObject("transport").getAsJsonArray("requests")) {
            JsonObject trace=item.getAsJsonObject();if(trace.has("clientRequestId")&&trace.get("clientRequestId").getAsString().equals(id))return trace;
        }
        Thread.sleep(100);
        }while(System.nanoTime()<deadline);
        fail("Missing bounded transport trace");return null;
    }
    JsonObject create(String name,int size)throws Exception {
        JsonObject json=new JsonObject();json.addProperty("name",name);json.addProperty("size",size);
        HttpResponse<byte[]> response=send("POST","/api/uploads",json.toString().getBytes(StandardCharsets.UTF_8),true);assertEquals(201,response.statusCode());return body(response);
    }
    static String sha(byte[] bytes)throws Exception{return TransferServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    String header(JsonObject upload,int size,String sha,String requestId,String extra) {
        return "PUT /api/uploads/"+upload.get("id").getAsString()+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+
                "\r\nAuthorization: Bearer "+session+"\r\nContent-Length: "+size+"\r\nX-Offset: 0\r\nX-Chunk-SHA256: "+sha+
                "\r\nX-Request-ID: "+requestId+"\r\n"+extra+"\r\n";
    }
    Socket socket()throws Exception {Socket socket=new Socket("127.0.0.1",server.getPort());socket.setSoTimeout(3000);return socket;}
    static String readHeaders(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int end=0;
        while(end<4) {int next=input.read();assertTrue(next>=0,"Response headers must complete");bytes.write(next);int expected=end==0||end==2?'\r':'\n';end=next==expected?end+1:next=='\r'?1:0;assertTrue(bytes.size()<16384);}
        return bytes.toString(StandardCharsets.ISO_8859_1);
    }

    @Test void snapshotsRequireAuthenticationAndExcludeSensitiveValues()throws Exception {
        assertEquals(401,send("GET","/api/diagnostics",null,false).statusCode());
        assertEquals(401,send("POST","/api/diagnostics/probe",new byte[]{1},false).statusCode());
        String name="PRIVATE-name-example.mov",id=UUID.randomUUID().toString();byte[] data="PRIVATE-payload-string".getBytes(StandardCharsets.UTF_8);
        JsonObject upload=create(name,data.length);
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+upload.get("id").getAsString()))
                .header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .header("X-Offset","0").header("X-Chunk-SHA256",sha(data)).header("X-Request-ID",id)
                .header("User-Agent","Mozilla/5.0 (iPhone; SECRET-user-agent) AppleWebKit")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        assertEquals(200,client.send(request,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        JsonObject current=trace(id);assertEquals("ios",current.get("platform").getAsString());assertEquals("upload-chunk",current.get("route").getAsString());
        assertEquals(data.length,current.get("receivedBytes").getAsLong());assertEquals(200,current.get("status").getAsInt());assertTrue(current.get("flushed").getAsBoolean());
        for(String metric:List.of("bodyRead","chunkHash","fileWrite","dataSync","metadataSync","responseFlush","uploadLockWait"))assertTrue(current.getAsJsonObject("durationMs").has(metric),metric);
        String report=snapshot().toString();
        for(String secret:List.of(name,"PRIVATE-payload-string",session,server.getPairToken(),upload.get("id").getAsString(),upload.get("resumeKey").getAsString(),"127.0.0.1","SECRET-user-agent",sha(data)))assertFalse(report.contains(secret),"No private request data in diagnostic export");
        assertEquals(403,client.send(HttpRequest.newBuilder(URI.create(base+"/api/diagnostics")).header("Authorization","Bearer "+session).header("Origin","https://invalid.example").GET().build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode());
    }

    @Test void partialBodyIsVisibleWithoutWaitingForUploadMonitorAndReportsEof()throws Exception {
        byte[] data=new byte[9];JsonObject upload=create("partial.bin",data.length);String id=UUID.randomUUID().toString();
        try(Socket socket=socket()) {
            OutputStream output=socket.getOutputStream();output.write(header(upload,data.length,sha(data),id,"").getBytes(StandardCharsets.US_ASCII));output.write(data,0,3);output.flush();
            JsonObject active=null;long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            do {active=trace(id);if(active.get("receivedBytes").getAsLong()==3)break;Thread.sleep(10);}while(System.nanoTime()<deadline);
            assertEquals(3,active.get("receivedBytes").getAsLong());assertFalse(active.get("closed").getAsBoolean());assertFalse(active.get("flushed").getAsBoolean());
            assertEquals(9,active.get("contentLength").getAsInt());
            socket.shutdownOutput();assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        JsonObject finished=trace(id);assertEquals("incomplete_body",finished.get("errorCode").getAsString());assertEquals(400,finished.get("status").getAsInt());assertEquals(3,finished.get("receivedBytes").getAsLong());
    }

    @Test void framedRequestRejectionPreservesSafeWireEvidence()throws Exception {
        String id=UUID.randomUUID().toString();
        try(Socket socket=socket()) {
            socket.getOutputStream().write(("POST /api/diagnostics/probe HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nTransfer-Encoding: chunked\r\nX-Request-ID: "+id+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        JsonObject rejected=trace(id);assertEquals("chunked",rejected.get("transferEncoding").getAsString());assertEquals("unsupported_encoding",rejected.get("errorCode").getAsString());assertEquals(0,rejected.get("receivedBytes").getAsInt());
    }

    @Test void standardContinueHandshakePrecedesBodyButNotHeaderRejection()throws Exception {
        byte[] data="continue-body".getBytes(StandardCharsets.UTF_8);JsonObject upload=create("continue.bin",data.length);String id=UUID.randomUUID().toString();
        try(Socket socket=socket()) {
            OutputStream output=socket.getOutputStream();output.write(header(upload,data.length,sha(data),id,"Expect: 100-Continue\r\n").getBytes(StandardCharsets.US_ASCII));output.flush();
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 100 Continue"));
            output.write(data);output.flush();assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 OK"));
        }
        JsonObject current=trace(id);assertEquals("100-continue",current.get("expect").getAsString());assertTrue(current.getAsJsonObject("atMs").has("continue-flushed"));assertEquals(data.length,current.get("receivedBytes").getAsInt());
        try(Socket socket=socket()) {
            socket.getOutputStream().write(("POST /api/diagnostics/probe HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nContent-Length: 1\r\nExpect: 100-continue\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 401"),"Reject unauthorized request without encouraging its body");
        }
        try(Socket socket=socket()) {
            socket.getOutputStream().write(("POST /api/diagnostics/probe HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nContent-Length: 1\r\nExpect: invalid\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 417"));
        }
    }

    @Test void probesReturnOnlyLengthAndDigestAndLeaveNoDiskFiles()throws Exception {
        byte[] bytes=new byte[512*1024];new Random(33).nextBytes(bytes);
        for(String method:List.of("POST","PUT")) {
            HttpResponse<byte[]> response=send(method,"/api/diagnostics/probe",bytes,true);assertEquals(200,response.statusCode());JsonObject result=body(response);
            assertEquals(2,result.size());assertEquals(bytes.length,result.get("length").getAsInt());assertEquals(sha(bytes),result.get("sha256").getAsString());
        }
        assertEquals(413,send("PUT","/api/diagnostics/probe",new byte[512*1024+1],true).statusCode());
        assertEquals(405,send("GET","/api/diagnostics/probe",null,true).statusCode());
        try(java.util.stream.Stream<Path> files=Files.walk(directory)){assertEquals(0,files.filter(Files::isRegularFile).count());}
        assertEquals(2,snapshot().getAsJsonObject("engine").get("uploadSlotsAvailable").getAsInt());
    }

    HttpResponse<byte[]> chunk(JsonObject upload,String method,long offset,byte[] data,String checksum,boolean auth)throws Exception {
        String path="/api/uploads/"+upload.get("id").getAsString()+(method.equals("POST")?"/chunk":"");
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).header("X-Upload-Key",upload.get("resumeKey").getAsString())
                .header("X-Offset",Long.toString(offset)).header("X-Chunk-SHA256",checksum);
        if(auth)request.header("Authorization","Bearer "+session);
        return client.send(request.method(method,HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test void postAliasSharesPutDurabilityIdempotencyAndAuthentication()throws Exception {
        byte[] data="abcdefghijkl".getBytes(StandardCharsets.UTF_8),first=Arrays.copyOfRange(data,0,4),middle=Arrays.copyOfRange(data,4,8),last=Arrays.copyOfRange(data,8,12);
        JsonObject upload=create("post-alias.bin",data.length);String path="/api/uploads/"+upload.get("id").getAsString();
        assertEquals(401,chunk(upload,"POST",0,first,sha(first),false).statusCode());
        assertEquals(4,body(chunk(upload,"PUT",0,first,sha(first),true)).get("offset").getAsInt());
        assertEquals(4,body(chunk(upload,"POST",0,first,sha(first),true)).get("offset").getAsInt(),"POST acknowledges the same already committed PUT");
        String other=body(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),false)).get("session").getAsString();
        HttpRequest.Builder recovering=HttpRequest.newBuilder(URI.create(base+path+"/chunk")).header("Authorization","Bearer "+other)
                .header("X-Offset","4").header("X-Chunk-SHA256",sha(middle));
        assertEquals(403,client.send(recovering.POST(HttpRequest.BodyPublishers.ofByteArray(middle)).build(),HttpResponse.BodyHandlers.ofByteArray()).statusCode(),"A different session still requires the recovery key");
        assertEquals(8,body(client.send(recovering.header("X-Upload-Key",upload.get("resumeKey").getAsString()).POST(HttpRequest.BodyPublishers.ofByteArray(middle)).build(),HttpResponse.BodyHandlers.ofByteArray())).get("offset").getAsInt());
        assertEquals(8,body(chunk(upload,"PUT",4,middle,sha(middle),true)).get("offset").getAsInt(),"PUT acknowledges the same already committed POST");
        assertEquals(409,chunk(upload,"POST",0,first,sha(first),true).statusCode(),"Old offsets never append twice");
        assertEquals(422,chunk(upload,"POST",8,last,"0".repeat(64),true).statusCode());
        assertEquals(8,body(send("GET",path,null,true)).get("offset").getAsInt());
        assertEquals(405,send("GET",path+"/chunk",null,true).statusCode());
        assertEquals(12,body(chunk(upload,"PUT",8,last,sha(last),true)).get("offset").getAsInt());
        assertEquals(200,send("POST",path+"/complete",("{\"sha256\":\""+sha(data)+"\"}").getBytes(StandardCharsets.UTF_8),true).statusCode());
        assertArrayEquals(data,Files.readAllBytes(server.receivedFile(upload.get("id").getAsString()).toPath()));
    }

    @Test void diagnosticRingAndCorrelationValuesAreBounded()throws Exception {
        for(int i=0;i<TransportDiagnostics.CAPACITY+5;i++)assertEquals(200,send("GET","/api/state",null,true).statusCode());
        JsonObject result=snapshot().getAsJsonObject("transport");assertTrue(result.getAsJsonArray("requests").size()<=TransportDiagnostics.CAPACITY);assertEquals(TransportDiagnostics.CAPACITY,result.get("capacity").getAsInt());
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/state")).header("Authorization","Bearer "+session).header("X-Request-ID","PRIVATE-arbitrary-value").GET().build();
        assertEquals(200,client.send(request,HttpResponse.BodyHandlers.ofByteArray()).statusCode());assertFalse(snapshot().toString().contains("PRIVATE-arbitrary-value"));
    }
}
