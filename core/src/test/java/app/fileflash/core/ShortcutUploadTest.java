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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ShortcutUploadTest {
    @TempDir Path directory;
    TransferServer server;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final List<String> openedAssets=new CopyOnWriteArrayList<>();
    byte[] shortcutBytes={1,2,3};
    String base,session,clientId;

    @BeforeEach void open() throws Exception {
        server=new TransferServer(directory.toFile(),name->{openedAssets.add(name);return name.equals("FileFlash.shortcut")&&shortcutBytes!=null?new ByteArrayInputStream(shortcutBytes):null;});
        server.start(0);base="http://127.0.0.1:"+server.getPort();
        JsonObject paired=object(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\",\"name\":\"iPhone\"}").getBytes(StandardCharsets.UTF_8),null,"application/json"));
        session=paired.get("session").getAsString();clientId=paired.get("clientId").getAsString();
    }
    @AfterEach void close(){server.stop();}
    static JsonObject object(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    static String sha(byte[] bytes)throws Exception{return TransferServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String target(String name){return "/api/shortcuts/files?name="+URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");}
    HttpResponse<byte[]> send(String method,String target,byte[] data,String token,String mime)throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+target)).timeout(Duration.ofSeconds(3));
        if(token!=null)request.header("Authorization","Bearer "+token);if(mime!=null)request.header("Content-Type",mime);
        return client.send(request.method(method,data==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(data)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    JsonObject upload(String name,byte[] data,String mime)throws Exception {
        HttpResponse<byte[]> response=send("POST",target(name),data,session,mime);assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return object(response);
    }
    JsonObject state(){return JsonParser.parseString(server.state()).getAsJsonObject();}
    void noRecords()throws Exception {
        assertEquals(0,state().getAsJsonArray("uploads").size());assertEquals(0,state().getAsJsonArray("files").size());
        try(var files=Files.list(directory.resolve("uploads"))){assertEquals(0,files.count());}
        try(var files=Files.list(directory.resolve("received"))){assertEquals(0,files.count());}
    }
    Socket socket()throws Exception {Socket socket=new Socket("127.0.0.1",server.getPort());socket.setSoTimeout(3000);return socket;}
    String header(String target,String length,boolean expect) {
        return "POST "+target+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\n"+(length==null?"":"Content-Length: "+length+"\r\n")+(expect?"Expect: 100-continue\r\n":"")+"\r\n";
    }
    String chunkedHeader(String target,boolean expect) {
        return header(target,null,expect).replace("\r\n\r\n","\r\nTransfer-Encoding: chunked\r\n\r\n");
    }
    String readHeader(InputStream input)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int ending=0;
        while(ending<4){int next=input.read();assertTrue(next>=0);bytes.write(next);int wanted=ending==0||ending==2?'\r':'\n';ending=next==wanted?ending+1:next=='\r'?1:0;assertTrue(bytes.size()<16384);}return bytes.toString(StandardCharsets.ISO_8859_1);
    }
    JsonObject active(String name)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        do {for(JsonElement value:state().getAsJsonArray("uploads")){JsonObject task=value.getAsJsonObject();if(task.get("name").getAsString().equals(name)&&task.get("offset").getAsLong()>0)return task;}Thread.sleep(5);}while(System.nanoTime()<deadline);
        fail("A partial body must be written and show progress before its sender finishes");return null;
    }
    void awaitNoRecords()throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        while(state().getAsJsonArray("uploads").size()!=0&&System.nanoTime()<deadline)Thread.sleep(5);noRecords();
    }
    void startPartial(Socket socket,String name,long length)throws Exception {
        OutputStream output=socket.getOutputStream();output.write(header(target(name),Long.toString(length),false).getBytes(StandardCharsets.US_ASCII));output.write(new byte[128*1024+19]);output.flush();active(name);
    }

    @Test void pairingBearerOriginAndMethodGuardsPrecedeAnyFileCreation()throws Exception {
        byte[] data={0,1,2};
        assertEquals(401,send("POST",target("photo.heic"),data,null,"image/heic").statusCode());
        assertEquals(401,send("POST",target("photo.heic"),data,"invalid","image/heic").statusCode());
        assertEquals(405,send("GET",target("photo.heic"),null,session,null).statusCode());
        HttpRequest crossSite=HttpRequest.newBuilder(URI.create(base+target("photo.heic"))).header("Authorization","Bearer "+session).header("Origin","https://evil.invalid").POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        assertEquals(403,client.send(crossSite,HttpResponse.BodyHandlers.ofByteArray()).statusCode());noRecords();
        server.revokeClient(clientId);assertEquals(401,send("POST",target("photo.heic"),data,session,"image/heic").statusCode());noRecords();
    }

    @Test void heicMovAndFileBytesRemainUnmodifiedAndAvailableToAnotherPairedClient()throws Exception {
        for(String name:List.of("原图 + & 😀.HEIC","原视频.MOV","安装包.apk")) {
            byte[] data=new byte[3*1024*1024+23];new Random(name.hashCode()).nextBytes(data);
            byte[] signature=(name.endsWith(".MOV")?"ftypqt  ":"ftypheic").getBytes(StandardCharsets.US_ASCII);System.arraycopy(signature,0,data,4,signature.length);
            String mime=name.endsWith(".HEIC")?"image/heic":name.endsWith(".MOV")?"video/quicktime":"application/vnd.android.package-archive";
            JsonObject receipt=upload(name,data,mime);String id=receipt.get("id").getAsString();
            assertEquals(name,receipt.get("name").getAsString());assertEquals(mime,receipt.get("mime").getAsString());assertEquals("complete",receipt.get("status").getAsString());assertEquals(data.length,receipt.get("size").getAsLong());assertEquals(sha(data),receipt.get("sha256").getAsString());
            assertTrue(receipt.get("temporary").getAsBoolean());assertTrue(receipt.get("restartOnly").getAsBoolean());assertFalse(receipt.has("resumeKey"));assertArrayEquals(data,Files.readAllBytes(server.receivedFile(id).toPath()));
            JsonObject paired=object(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),null,"application/json"));
            assertArrayEquals(data,send("GET","/api/files/"+id,null,paired.get("session").getAsString(),null).body());
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
            while(server.reclaimableCacheBytes()!=data.length&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(data.length,server.reclaimableCacheBytes());
            assertEquals(data.length,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));noRecords();
        }
        assertTrue(openedAssets.isEmpty());
    }

    @Test void emptyFileAndLongUnicodeNameAreSafeWithoutReadingASecondCopy()throws Exception {
        String longName="照片😀".repeat(35)+".heic";JsonObject result=upload(longName,new byte[0],"image/heic; charset=binary");String id=result.get("id").getAsString();
        assertEquals(0,server.receivedFile(id).length());assertEquals(sha(new byte[0]),result.get("sha256").getAsString());assertEquals("image/heic",result.get("mime").getAsString());
        assertTrue(result.get("name").getAsString().getBytes(StandardCharsets.UTF_8).length<=180);
        assertTrue(result.get("name").getAsString().endsWith(".heic"),"Truncating a long title must retain its original extension");
        assertEquals(TransferServer.safeName(result.get("name").getAsString()),result.get("name").getAsString());
        assertEquals("literal+plus.bin",object(send("POST","/api/shortcuts/files?name=literal+plus.bin",new byte[0],session,null)).get("name").getAsString());
    }

    @Test void invalidNamesAndOversizedMetadataNeverCreateTasks()throws Exception {
        for(String query:List.of("","?other=x","?name=","?name=%20","?name=..","?name=%00photo","?name=%0Aphoto","?name=folder%2Fphoto","?name=folder%5Cphoto","?name=%C0%AF","?name=x&name=y")) {
            assertEquals(400,send("POST","/api/shortcuts/files"+query,new byte[0],session,null).statusCode(),query);noRecords();
        }
        for(String bad:List.of("%","%GG","%E0%A4"))try(Socket socket=socket()) {
            socket.getOutputStream().write(header("/api/shortcuts/files?name="+bad,"0",false).getBytes(StandardCharsets.US_ASCII));assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        assertEquals(413,send("POST",target("界".repeat(342)),new byte[0],session,null).statusCode());
        assertEquals(413,send("POST",target("x".repeat(4097)),new byte[0],session,null).statusCode());noRecords();
    }

    @Test void contentLengthIsRequiredAndBadLengthsOrInsufficientStorageFailBeforeContinue()throws Exception {
        for(String length:Arrays.asList(null,"-1","9223372036854775808","9223372036854775807"))try(Socket socket=socket()) {
            socket.getOutputStream().write(header(target("photo.heic"),length,true).getBytes(StandardCharsets.US_ASCII));String response=readHeader(socket.getInputStream());
            assertTrue(response.startsWith("HTTP/1.1 "+(length==null?411:length.equals("9223372036854775807")?507:400)),response);assertFalse(response.contains("100 Continue"));
        }
        noRecords();
    }

    @Test void eofDeletesBodyPrefixMetadataAndShare()throws Exception {
        try(Socket socket=socket()) {
            startPartial(socket,"interrupted.mov",4*1024*1024);socket.shutdownOutput();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        awaitNoRecords();assertEquals(0,server.reclaimableCacheBytes());
    }

    @Test void hostCancellationAndSessionRevocationDeleteActiveDataAndReleaseSlots()throws Exception {
        try(Socket socket=socket()) {
            startPartial(socket,"cancel.mov",4*1024*1024);JsonObject task=active("cancel.mov");server.cancelUpload(task.get("id").getAsString());awaitNoRecords();
        }
        try(Socket socket=socket()) {
            startPartial(socket,"revoked.mov",4*1024*1024);server.revokeClient(clientId);awaitNoRecords();
        }
        JsonObject paired=object(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),null,"application/json"));session=paired.get("session").getAsString();
        assertEquals("complete",upload("retry.mov",new byte[]{1,2,3},"video/quicktime").get("status").getAsString());
    }

    @Test void twoStreamsShareUploadSlotsAndDiskReservationsWithNativeProtocol()throws Exception {
        long usable=directory.toFile().getUsableSpace();Assumptions.assumeTrue(usable>128L*1024*1024);long reserve=Math.min(512L*1024*1024,usable/8);
        try(Socket first=socket();Socket second=socket();Socket third=socket()) {
            startPartial(first,"one.mov",reserve);startPartial(second,"two.mov",reserve);
            third.getOutputStream().write(header(target("three.mov"),"3",true).getBytes(StandardCharsets.US_ASCII));assertTrue(readHeader(third.getInputStream()).startsWith("HTTP/1.1 429"));
            assertEquals(2,state().getAsJsonArray("uploads").size());assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()),"An explicit cleanup cannot erase either active stream");
            assertEquals(200,send("GET","/api/state",null,session,null).statusCode());
            long creationSize=directory.toFile().getUsableSpace()-8L*1024*1024;
            byte[] nativeMetadata=("{\"name\":\"reserved.bin\",\"size\":"+creationSize+"}").getBytes(StandardCharsets.UTF_8);
            assertEquals(507,send("POST","/api/uploads",nativeMetadata,session,"application/json").statusCode(),"Native creation must account for the pending bytes in both Shortcuts streams");
        }
        awaitNoRecords();assertEquals("complete",upload("after-slots.bin",new byte[]{7},null).get("status").getAsString());
    }

    @Test void manualSaveIntentProtectsPrivateFileAndCompletedTemporaryDataRecovers()throws Exception {
        byte[] data={9,8,7,6};String id=upload("keep.heic",data,"image/heic").get("id").getAsString();
        server.stop();open();assertArrayEquals(data,Files.readAllBytes(server.receivedFile(id).toPath()));assertEquals(data.length,server.reclaimableCacheBytes());
        try(TransferServer.ReceivedSave save=server.claimReceivedSave(id)) {
            assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertArrayEquals(data,Files.readAllBytes(save.file().toPath()));assertFalse(state().getAsJsonArray("files").get(0).getAsJsonObject().get("temporary").getAsBoolean());
        }
        assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));assertEquals(data.length,server.protectedReceivedBytes());
        server.stop();open();assertEquals(0,server.reclaimableCacheBytes());assertArrayEquals(data,Files.readAllBytes(server.receivedFile(id).toPath()));
    }

    @Test void shortcutAttachmentIsFixedPublicAndDoesNotExposeOtherAssetsOrPairingSecrets()throws Exception {
        String token=server.getPairToken();HttpResponse<byte[]> response=send("GET","/FileFlash.shortcut",null,null,null);
        assertEquals(200,response.statusCode());assertArrayEquals(shortcutBytes,response.body());assertEquals("application/x-apple-shortcut",response.headers().firstValue("content-type").orElseThrow());
        assertEquals("attachment; filename=\"FileFlash.shortcut\"",response.headers().firstValue("content-disposition").orElseThrow());assertEquals("no-store",response.headers().firstValue("cache-control").orElseThrow());
        HttpResponse<byte[]> head=send("HEAD","/FileFlash.shortcut",null,null,null);assertEquals(200,head.statusCode());assertEquals(0,head.body().length);assertEquals("3",head.headers().firstValue("content-length").orElseThrow());
        assertEquals(List.of("FileFlash.shortcut","FileFlash.shortcut"),openedAssets);
        assertEquals(404,send("GET","/ios/FileFlash.shortcut",null,null,null).statusCode());assertEquals(404,send("GET","/password.txt",null,null,null).statusCode());assertEquals(405,send("POST","/FileFlash.shortcut",null,null,null).statusCode());
        assertEquals(2,openedAssets.size());assertEquals(token,server.getPairToken());assertFalse(new String(response.body(),StandardCharsets.UTF_8).contains(token));
    }

    @Test void missingOrEmptyShortcutAttachmentReturnsNotFoundWithoutPairingMutation()throws Exception {
        String token=server.getPairToken();shortcutBytes=null;assertEquals(404,send("GET","/FileFlash.shortcut",null,null,null).statusCode());
        shortcutBytes=new byte[0];assertEquals(404,send("GET","/FileFlash.shortcut",null,null,null).statusCode());assertEquals(token,server.getPairToken());noRecords();
    }

    @Test void standardHttpClientUnknownLengthBodyStreamsWithoutConversionOrClientChecksum()throws Exception {
        byte[] data=new byte[9*1024*1024+51];new Random(89).nextBytes(data);
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+target("原格式.heic"))).timeout(Duration.ofSeconds(4)).expectContinue(true).header("Authorization","Bearer "+session).header("Content-Type","image/heic").POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(data))).build();
        HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());JsonObject result=object(response);
        assertEquals(data.length,result.get("size").getAsLong());assertTrue(result.get("lengthKnown").getAsBoolean());assertEquals(sha(data),result.get("sha256").getAsString());assertArrayEquals(data,Files.readAllBytes(server.receivedFile(result.get("id").getAsString()).toPath()));
        JsonObject diagnostics=object(send("GET","/api/diagnostics",null,session,null));boolean found=false;
        for(JsonElement value:diagnostics.getAsJsonObject("transport").getAsJsonArray("requests")) {
            JsonObject trace=value.getAsJsonObject();if(!trace.get("route").getAsString().equals("shortcut-upload"))continue;
            assertFalse(trace.get("hasContentLength").getAsBoolean());assertEquals("chunked",trace.get("transferEncoding").getAsString());assertEquals(data.length,trace.get("receivedBytes").getAsLong());found=true;
        }
        assertTrue(found);assertFalse(diagnostics.toString().contains("原格式"));assertFalse(diagnostics.toString().contains(session));
    }

    @Test void chunkedContinueEmptyBodyExtensionsAndTrailersHaveNoPerChunkAcknowledgement()throws Exception {
        try(Socket socket=socket()) {
            OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("empty.mov"),true).getBytes(StandardCharsets.US_ASCII));out.flush();
            assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 100 Continue"));out.write("0;empty=yes\r\nDigest: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 200"));
        }
        JsonObject empty=state().getAsJsonArray("uploads").get(0).getAsJsonObject();assertEquals(0,empty.get("size").getAsLong());assertEquals(sha(new byte[0]),empty.get("sha256").getAsString());server.cleanupStaleUploads(0,0,System.currentTimeMillis());
        try(Socket socket=socket()) {
            OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("extensions.mov"),false).getBytes(StandardCharsets.US_ASCII));out.write("3;kind=raw;note=\"a;b\\\"c\"\r\nabc\r\n2\r\nde\r\n0\r\nX-Trace: ignored\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 200"));
        }
        JsonObject result=state().getAsJsonArray("files").get(0).getAsJsonObject();assertArrayEquals("abcde".getBytes(StandardCharsets.US_ASCII),Files.readAllBytes(server.receivedFile(result.get("id").getAsString()).toPath()));
    }

    @Test void ambiguousEncodingsAndChunkingOutsideTheNewRouteAreRejectedBeforeCreatingData()throws Exception {
        List<String> headers=List.of(
                chunkedHeader(target("bad.bin"),true).replace("Transfer-Encoding: chunked","Transfer-Encoding: chunked\r\nContent-Length: 0"),
                chunkedHeader(target("bad.bin"),true).replace("Transfer-Encoding: chunked","Transfer-Encoding: gzip, chunked"),
                chunkedHeader(target("bad.bin"),true).replace("Transfer-Encoding: chunked","Transfer-Encoding: chunked\r\nTransfer-Encoding: chunked"),
                chunkedHeader("/api/uploads",true),chunkedHeader("/api/pair",true),chunkedHeader("/api/shortcuts/files-extra?name=x",true));
        for(String header:headers)try(Socket socket=socket()) {
            socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));String response=readHeader(socket.getInputStream());assertTrue(response.startsWith("HTTP/1.1 400"),response);
        }
        noRecords();
    }

    @Test void incompleteOrMalformedChunkFramesNeverPromoteAPrefix()throws Exception {
        for(String payload:List.of("3\r\nab","3\r\nabc","3\r\nabcX\n0\r\n\r\n","z\r\n","8000000000000000\r\n","1;\r\nx\r\n0\r\n\r\n","0\r\nContent-Length: 0\r\n\r\n","0\r\nX-End: no-final-empty-line\r\n")) {
            try(Socket socket=socket()) {
                OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("bad.mov"),false).getBytes(StandardCharsets.US_ASCII));out.write(payload.getBytes(StandardCharsets.US_ASCII));out.flush();socket.shutdownOutput();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 400"),payload);
            }
            awaitNoRecords();
        }
    }

    @Test void chunkExtensionAndTrailerBudgetsAreBoundedAndFailuresCleanTheTask()throws Exception {
        for(String payload:List.of("1;data="+"x".repeat(4096)+"\r\nx\r\n0\r\n\r\n","0\r\n"+("X-Note: "+"x".repeat(1000)+"\r\n").repeat(17)+"\r\n")) {
            try(Socket socket=socket()) {
                OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("metadata.mov"),false).getBytes(StandardCharsets.US_ASCII));out.write(payload.getBytes(StandardCharsets.US_ASCII));out.flush();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 413"));
            }
            awaitNoRecords();
        }
    }

    @Test void unknownLengthProgressCleanupCancellationAndDeclaredReservationsRemainConsistent()throws Exception {
        long usable=directory.toFile().getUsableSpace();Assumptions.assumeTrue(usable>128L*1024*1024);
        try(Socket socket=socket()) {
            OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("unknown.mov"),false).getBytes(StandardCharsets.US_ASCII));out.write("40000\r\n".getBytes(StandardCharsets.US_ASCII));out.write(new byte[128*1024+19]);out.flush();JsonObject task=active("unknown.mov");
            assertFalse(task.get("lengthKnown").getAsBoolean());assertTrue(task.get("offset").getAsLong()<=task.get("size").getAsLong());assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));
            byte[] reservation=("{\"name\":\"native-reservation.bin\",\"size\":"+(directory.toFile().getUsableSpace()-4L*1024*1024-64*1024)+"}").getBytes(StandardCharsets.UTF_8);
            HttpResponse<byte[]> created=send("POST","/api/uploads",reservation,session,"application/json");assertEquals(201,created.statusCode());String nativeId=object(created).get("id").getAsString();
            out.write(new byte[128*1024-19]);out.flush();assertTrue(readHeader(socket.getInputStream()).startsWith("HTTP/1.1 507"),"The unknown stream cannot consume bytes reserved for a declared native upload");
            server.cancelUpload(nativeId);server.cleanupStaleUploads(0,0,System.currentTimeMillis());
        }
        long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();
        while(state().getAsJsonArray("uploads").size()>1&&System.nanoTime()<deadline)Thread.sleep(5);
        assertEquals(1,state().getAsJsonArray("uploads").size());assertEquals("cancelled",state().getAsJsonArray("uploads").get(0).getAsJsonObject().get("status").getAsString());
        server.stop();open();noRecords();
        try(Socket socket=socket()) {
            OutputStream out=socket.getOutputStream();out.write(chunkedHeader(target("stopped.mov"),false).getBytes(StandardCharsets.US_ASCII));out.write("100000\r\n".getBytes(StandardCharsets.US_ASCII));out.write(new byte[8193]);out.flush();active("stopped.mov");server.stop();
        }
        noRecords();open();noRecords();
    }

    @Test void chunkedDecodedOffsetsRemainLongBeyondFourGiBWithoutAllocatingTheFile()throws Exception {
        long size=(1L<<32)+37;
        InputStream zeroes=new InputStream(){long remaining=size;@Override public int read(){if(remaining==0)return -1;remaining--;return 0;}@Override public int read(byte[] bytes,int offset,int length){if(remaining==0)return -1;int count=(int)Math.min(length,remaining);remaining-=count;return count;}};
        InputStream encoded=new SequenceInputStream(Collections.enumeration(List.of(new ByteArrayInputStream((Long.toHexString(size)+"\r\n").getBytes(StandardCharsets.US_ASCII)),zeroes,new ByteArrayInputStream("\r\n0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)))));
        TransportDiagnostics.Trace trace=new TransportDiagnostics().accepted();HttpServer.Request request=new HttpServer.Request("POST","/api/shortcuts/files","name=large.mov",Map.of("transfer-encoding","chunked"),-1,true,encoded,null,trace);
        byte[] buffer=new byte[128*1024];while(request.readStreamBody(buffer)!=-1)assertTrue(request.consumed<=size);
        assertEquals(size,request.consumed);assertEquals(size,trace.receivedBytes);assertEquals(-1,request.readStreamBody(buffer));
    }

    @Test void slowNativeChunkDoesNotBlockShortcutCreationStreamingOrNativeDeduplication()throws Exception {
        byte[] nativeBytes=new byte[512*1024];JsonObject metadata=new JsonObject();metadata.addProperty("name","slow-native.bin");metadata.addProperty("size",nativeBytes.length);metadata.addProperty("sha256",sha(nativeBytes));
        HttpResponse<byte[]> creation=send("POST","/api/uploads",metadata.toString().getBytes(StandardCharsets.UTF_8),session,"application/json");assertEquals(201,creation.statusCode());String id=object(creation).get("id").getAsString();
        try(Socket slow=socket()) {
            String request="PUT /api/uploads/"+id+" HTTP/1.1\r\nHost: 127.0.0.1:"+server.getPort()+"\r\nAuthorization: Bearer "+session+"\r\nX-Offset: 0\r\nX-Chunk-SHA256: "+sha(nativeBytes)+"\r\nContent-Length: "+nativeBytes.length+"\r\n\r\n";
            slow.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));slow.getOutputStream().write(new byte[1024]);slow.getOutputStream().flush();
            long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();boolean reading=false;
            do {
                JsonObject diagnostics=object(send("GET","/api/diagnostics",null,session,null));
                for(JsonElement value:diagnostics.getAsJsonObject("transport").getAsJsonArray("requests")) {
                    JsonObject trace=value.getAsJsonObject();JsonObject stages=trace.getAsJsonObject("atMs");
                    if(trace.get("route").getAsString().equals("upload-chunk")&&stages.has("body-reading")&&!stages.has("body-complete")&&!trace.get("closed").getAsBoolean())reading=true;
                }
                if(!reading)Thread.sleep(5);
            }while(!reading&&System.nanoTime()<deadline);
            assertTrue(reading,"The native worker must be waiting inside its partial request body");
            long started=System.nanoTime();HttpResponse<byte[]> duplicate=send("POST","/api/uploads",metadata.toString().getBytes(StandardCharsets.UTF_8),session,"application/json");assertEquals(200,duplicate.statusCode());assertEquals(id,object(duplicate).get("id").getAsString());
            byte[] media=new byte[256*1024+3];new Random(19).nextBytes(media);
            HttpRequest shortcut=HttpRequest.newBuilder(URI.create(base+target("parallel.mov"))).timeout(Duration.ofSeconds(2)).header("Authorization","Bearer "+session).POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(media))).build();
            HttpResponse<byte[]> response=client.send(shortcut,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());
            assertArrayEquals(media,Files.readAllBytes(server.receivedFile(object(response).get("id").getAsString()).toPath()));
            assertTrue(System.nanoTime()-started<Duration.ofSeconds(1).toNanos(),"The native network read cannot monopolize the global creation or unknown-stream write guards");
            assertEquals(0,state().getAsJsonArray("uploads").asList().stream().map(JsonElement::getAsJsonObject).filter(task->task.get("id").getAsString().equals(id)).findFirst().orElseThrow().get("offset").getAsLong());
            server.cancelUpload(id);
        }
    }

    @Test void unknownStreamCannotPublishKnownLengthWithAnOldPartialProgressSnapshot()throws Exception {
        byte[] data=new byte[1024*1024+3];new Random(20).nextBytes(data);
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+target("snapshot.mov"))).timeout(Duration.ofSeconds(4)).header("Authorization","Bearer "+session).POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(data){@Override public synchronized int read(byte[] target,int offset,int length){try{Thread.sleep(1);}catch(InterruptedException e){Thread.currentThread().interrupt();}return super.read(target,offset,Math.min(length,4096));}})).build();
        var future=client.sendAsync(request,HttpResponse.BodyHandlers.ofByteArray());int observed=0;
        while(!future.isDone()) {
            for(JsonElement value:state().getAsJsonArray("uploads")) {
                JsonObject task=value.getAsJsonObject();assertTrue(task.get("offset").getAsLong()<=task.get("size").getAsLong());
                if(task.get("lengthKnown").getAsBoolean()){assertEquals(data.length,task.get("size").getAsLong());assertEquals(data.length,task.get("offset").getAsLong());}
                observed++;
            }
            Thread.sleep(1);
        }
        HttpResponse<byte[]> response=future.get(4,TimeUnit.SECONDS);assertEquals(200,response.statusCode());assertTrue(observed>0);assertTrue(object(response).get("lengthKnown").getAsBoolean());assertEquals(data.length,object(response).get("size").getAsLong());
    }

    @Test void durableNativePrefixIsNotReservedTwiceWhenOnlyASmallAmountOfSpaceRemains()throws Exception {
        Assumptions.assumeTrue(directory.toFile().getUsableSpace()>128L*1024*1024);
        byte[] prefix=new byte[2*1024*1024];new Random(21).nextBytes(prefix);
        JsonObject metadata=new JsonObject();metadata.addProperty("name","almost-finished.bin");metadata.addProperty("size",prefix.length+1);
        HttpResponse<byte[]> created=send("POST","/api/uploads",metadata.toString().getBytes(StandardCharsets.UTF_8),session,"application/json");assertEquals(201,created.statusCode());String id=object(created).get("id").getAsString();
        HttpRequest chunk=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id)).header("Authorization","Bearer "+session).header("X-Offset","0").header("X-Chunk-SHA256",sha(prefix)).PUT(HttpRequest.BodyPublishers.ofByteArray(prefix)).build();
        assertEquals(200,client.send(chunk,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(prefix.length,object(send("GET","/api/uploads/"+id,null,session,null)).get("offset").getAsLong());
        // A zero-byte on-disk task reserves most remaining space without filling the test machine's disk.
        long usable=directory.toFile().getUsableSpace(),small=64*1024,headroom=256*1024;
        JsonObject reservation=new JsonObject();reservation.addProperty("name","space-reservation.bin");reservation.addProperty("size",usable-4L*1024*1024-small-headroom-1);
        HttpResponse<byte[]> reserved=send("POST","/api/uploads",reservation.toString().getBytes(StandardCharsets.UTF_8),session,"application/json");assertEquals(201,reserved.statusCode(),"The already synchronized 2 MiB prefix cannot be counted a second time");
        byte[] data=new byte[(int)small];new Random(22).nextBytes(data);
        HttpRequest shortcut=HttpRequest.newBuilder(URI.create(base+target("small.heic"))).header("Authorization","Bearer "+session).POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(data))).build();
        HttpResponse<byte[]> response=client.send(shortcut,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());assertArrayEquals(data,Files.readAllBytes(server.receivedFile(object(response).get("id").getAsString()).toPath()));
        try(var parts=Files.list(directory.resolve("uploads"))){assertEquals(2,parts.filter(path->path.toString().endsWith(".part")).count());}
    }
}
