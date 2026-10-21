package app.fileflash.core;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class MediaFilenameTest {
    @TempDir Path directory;
    TransferServer server;
    String base,session;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach void start() throws Exception {
        server=new TransferServer(directory.toFile(),name->new ByteArrayInputStream(new byte[0]));
        server.start(0);base="http://127.0.0.1:"+server.getPort();session=pair();
    }
    @AfterEach void close(){server.stop();}
    String pair() throws Exception {
        return json(send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),null,"application/json")).get("session").getAsString();
    }
    HttpResponse<byte[]> send(String method,String path,byte[] bytes,String token,String mime) throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(4));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(mime!=null)request.header("Content-Type",mime);
        return client.send(request.method(method,bytes==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static JsonObject json(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    static String encoded(String name){return URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20").replace("*","%2A");}
    JsonObject upload(String name,byte[] bytes,String mime) throws Exception {
        HttpResponse<byte[]> response=send("POST","/api/shortcuts/files?name="+encoded(name),bytes,session,mime);
        assertEquals(200,response.statusCode(),new String(response.body(),StandardCharsets.UTF_8));return json(response);
    }
    static byte[] bytes(int... values){byte[] result=new byte[values.length];for(int i=0;i<values.length;i++)result[i]=(byte)values[i];return result;}
    static byte[] ascii(String value){return value.getBytes(StandardCharsets.ISO_8859_1);}
    static byte[] ftyp(String major,String... compatible) {
        ByteBuffer out=ByteBuffer.allocate(16+4*compatible.length);out.putInt(out.capacity()).put(ascii("ftyp")).put(ascii(major)).putInt(0);
        for(String brand:compatible)out.put(ascii(brand));return out.array();
    }
    static byte[] join(byte[] first,byte[] second){byte[] all=Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,all,first.length,second.length);return all;}
    static String sha(byte[] bytes) throws Exception{return TransferServer.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    record Fixture(byte[] bytes,String extension,String mime) { }

    void assertReceived(JsonObject receipt,String name,String mime,byte[] bytes,String downloader) throws Exception {
        String id=receipt.get("id").getAsString();
        assertEquals(name,receipt.get("name").getAsString());assertEquals(mime,receipt.get("mime").getAsString());
        assertEquals("complete",receipt.get("status").getAsString());assertEquals(sha(bytes),receipt.get("sha256").getAsString());
        assertArrayEquals(bytes,Files.readAllBytes(server.receivedFile(id).toPath()));
        JsonObject metadata=JsonParser.parseString(Files.readString(directory.resolve("uploads").resolve(id+".json"))).getAsJsonObject();
        assertEquals(name,metadata.get("name").getAsString());assertEquals(name,metadata.get("finalName").getAsString());assertEquals(mime,metadata.get("mime").getAsString());
        HttpResponse<byte[]> download=send("GET","/api/files/"+id,null,downloader,null);
        assertEquals(200,download.statusCode());assertArrayEquals(bytes,download.body());
        assertEquals(mime,download.headers().firstValue("content-type").orElseThrow());
        String disposition=download.headers().firstValue("content-disposition").orElseThrow();
        assertTrue(disposition.endsWith("filename*=UTF-8''"+encoded(name)),disposition);
        String extension=name.substring(name.lastIndexOf('.')+1);
        assertTrue(disposition.startsWith("attachment; filename=\"download."+extension+"\";"),disposition);
    }

    @Test void extensionlessPhotosReceiveTheirOwnSignatureAndOriginalBytes() throws Exception {
        List<Fixture> fixtures=List.of(
                new Fixture(bytes(0xff,0xd8,0xff,0xe1,0,2),"jpg","image/jpeg"),
                new Fixture(bytes(0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a),"png","image/png"),
                new Fixture(ascii("GIF89a"),"gif","image/gif"),
                new Fixture(ascii("RIFF\0\0\0\0WEBPVP8 "),"webp","image/webp"),
                new Fixture(bytes(0x49,0x49,0x2a,0,8,0,0,0),"tif","image/tiff"),
                new Fixture(ascii("BM123456"),"bmp","image/bmp"));
        String other=pair();int index=0;
        for(Fixture fixture:fixtures) {
            String title="原图 "+(++index)+" + 😀";
            assertReceived(upload(title,fixture.bytes,"application/octet-stream"),title+"."+fixture.extension,fixture.mime,fixture.bytes,other);
        }
    }

    @Test void isoBrandsDistinguishHeicHeifAvifQuickTimeMp4AndAudio() throws Exception {
        List<Fixture> fixtures=List.of(
                new Fixture(ftyp("heic","mif1"),"heic","image/heic"),
                new Fixture(ftyp("mif1","heix"),"heic","image/heic"),
                new Fixture(ftyp("hevc","msf1"),"heics","image/heic-sequence"),
                new Fixture(ftyp("mif1"),"heif","image/heif"),
                new Fixture(ftyp("msf1","mif1"),"heifs","image/heif-sequence"),
                new Fixture(ftyp("avif","mif1","mp42"),"avif","image/avif"),
                new Fixture(ftyp("qt  "),"mov","video/quicktime"),
                new Fixture(ftyp("isom","iso2","mp41"),"mp4","video/mp4"),
                new Fixture(ftyp("M4V ","mp42"),"mp4","video/mp4"),
                new Fixture(ftyp("M4A ","mp42"),"m4a","audio/mp4"));
        int index=0;
        for(Fixture fixture:fixtures) {
            String title="media-"+(++index);
            assertReceived(upload(title,fixture.bytes,"application/octet-stream"),title+"."+fixture.extension,fixture.mime,fixture.bytes,session);
        }
    }

    @Test void existingExtensionsStayIntactWhileGenericMimeCanBeResolved() throws Exception {
        byte[] jpeg=bytes(0xff,0xd8,0xff,0xe1,0,2);
        assertReceived(upload("PHOTO.JPEG",jpeg,"application/octet-stream"),"PHOTO.JPEG","image/jpeg",jpeg,session);
        byte[] heic=ftyp("heic","mif1");
        assertReceived(upload("ORIGINAL.custom",heic,"application/octet-stream"),"ORIGINAL.custom","image/heic",heic,session);
        assertReceived(upload("retained.PNG",jpeg,"image/png"),"retained.PNG","image/png",jpeg,session);
        assertReceived(upload("document.pdf",ascii("%PDF-1.7"),"application/pdf"),"document.pdf","application/pdf",ascii("%PDF-1.7"),session);
        assertReceived(upload("package.apk",bytes(0x50,0x4b,3,4),"application/octet-stream"),"package.apk","application/octet-stream",bytes(0x50,0x4b,3,4),session);
    }

    @Test void aVersionSuffixInThePhotoTitleIsNotMistakenForTheRemovedImageExtension() throws Exception {
        byte[] jpeg=bytes(0xff,0xd8,0xff,0xe1,0,2);
        assertReceived(upload("trip.v2",jpeg,"application/octet-stream"),"trip.v2.jpg","image/jpeg",jpeg,session);
    }

    @Test void aNumericSuffixInTheVideoTitleIsPreservedBeforeTheRecoveredExtension() throws Exception {
        byte[] mov=ftyp("qt  ");
        assertReceived(upload("旅程.2026",mov,"application/octet-stream"),"旅程.2026.mov","video/quicktime",mov,session);
        assertReceived(upload("unknown.v2",ascii("unknown"),"application/octet-stream"),"unknown.v2","application/octet-stream",ascii("unknown"),session);
    }

    @Test void unknownTruncatedAndMisleadingPayloadsStayBinary() throws Exception {
        byte[] minorVersionIsNotABrand=ftyp("xxxx");System.arraycopy(ascii("heic"),0,minorVersionIsNotABrand,12,4);
        byte[] oversizedBox=ftyp("heic");ByteBuffer.wrap(oversizedBox).putInt(65540);
        byte[] embeddedSignature=join(bytes(1,2,3,4),ftyp("qt  "));
        List<byte[]> bodies=List.of(new byte[0],bytes(0xff,0xd8),ascii("arbitrary"),minorVersionIsNotABrand,
                Arrays.copyOf(ftyp("heic"),11),oversizedBox,embeddedSignature,ftyp("isom"),ftyp("xxxx"));
        int index=0;
        for(byte[] body:bodies) {
            String title="unknown-"+(++index);
            assertReceived(upload(title,body,"video/quicktime"),title+".bin","application/octet-stream",body,session);
        }
    }

    @Test void sniffingIsBoundedAndHandlesCompleteBoxesWithoutSearchingTheirPayloads() throws Exception {
        byte[] padding=new byte[65536];ByteBuffer.wrap(padding).putInt(padding.length).put(ascii("free"));
        byte[] beyondBound=join(padding,ftyp("heic"));
        assertReceived(upload("beyond-bound",beyondBound,null),"beyond-bound.bin","application/octet-stream",beyondBound,session);
        byte[] free=ByteBuffer.allocate(12).putInt(12).put(ascii("free")).putInt(0).array();
        byte[] mov=join(free,ftyp("qt  "));
        assertReceived(upload("padded",mov,null),"padded.mov","video/quicktime",mov,session);
        byte[] extended=ByteBuffer.allocate(24).putInt(1).put(ascii("ftyp")).putLong(24).put(ascii("heic")).putInt(0).array();
        assertReceived(upload("extended",extended,null),"extended.heic","image/heic",extended,session);
    }

    @Test void chunkedShortcutMediaUsesTheSameCompletionMetadataAndOriginalBytes() throws Exception {
        byte[] payload=new byte[2*1024*1024+29];new Random(7).nextBytes(payload);
        byte[] header=ftyp("qt  ");System.arraycopy(header,0,payload,0,header.length);
        HttpRequest request=HttpRequest.newBuilder(URI.create(base+"/api/shortcuts/files?name=chunked"))
                .timeout(Duration.ofSeconds(4)).expectContinue(true).header("Authorization","Bearer "+session)
                .header("Content-Type","application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(payload))).build();
        HttpResponse<byte[]> response=client.send(request,HttpResponse.BodyHandlers.ofByteArray());assertEquals(200,response.statusCode());
        assertReceived(json(response),"chunked.mov","video/quicktime",payload,pair());
    }

    @Test void correctedNamesPersistAcrossRestartAndRemainTheNamesUsedForHostSaving() throws Exception {
        byte[] payload=join(ftyp("heic","mif1"),new byte[70001]);
        JsonObject receipt=upload("相册原图",payload,"application/octet-stream");String id=receipt.get("id").getAsString();
        server.stop();start();
        JsonObject recovered=JsonParser.parseString(server.state()).getAsJsonObject().getAsJsonArray("uploads").get(0).getAsJsonObject();
        assertReceived(recovered,"相册原图.heic","image/heic",payload,pair());
        try(TransferServer.ReceivedSave save=server.claimReceivedSave(id)) {
            assertEquals("相册原图.heic",save.file().getName());assertArrayEquals(payload,Files.readAllBytes(save.file().toPath()));
            assertEquals(0,server.cleanupStaleUploads(0,0,System.currentTimeMillis()));
        }
        assertEquals(payload.length,server.protectedReceivedBytes());assertEquals(0,server.reclaimableCacheBytes());
        server.stop();start();
        assertEquals("相册原图.heic",server.receivedFile(id).getName());
        assertArrayEquals(payload,send("GET","/api/files/"+id,null,session,null).body());
    }

    @Test void longUnicodeNamesAndCollisionsKeepTheirExtensionsAfterRestart() throws Exception {
        byte[] jpeg=bytes(0xff,0xd8,0xff,0xe1,0,2);String title="照片😀".repeat(40);
        JsonObject first=upload(title,jpeg,null),second=upload(title,jpeg,null);
        String firstName=first.get("name").getAsString(),secondName=second.get("name").getAsString();
        assertTrue(firstName.endsWith(".jpg"));assertTrue(secondName.endsWith(" (2).jpg"));
        assertTrue(firstName.getBytes(StandardCharsets.UTF_8).length<=172);assertTrue(secondName.getBytes(StandardCharsets.UTF_8).length<=180);
        assertEquals(TransferServer.safeName(secondName),secondName);
        server.stop();start();
        assertEquals(firstName,server.receivedFile(first.get("id").getAsString()).getName());
        assertEquals(secondName,server.receivedFile(second.get("id").getAsString()).getName());
    }

    @Test void browserAndNativeProtocolsKeepIntentionalExtensionlessNames() throws Exception {
        byte[] payload=bytes(0xff,0xd8,0xff,0xe1,0,2);
        JsonObject metadata=new JsonObject();metadata.addProperty("name","README");metadata.addProperty("size",payload.length);metadata.addProperty("restartOnly",true);
        JsonObject task=json(send("POST","/api/uploads",metadata.toString().getBytes(StandardCharsets.UTF_8),session,"application/json"));
        JsonObject stream=json(send("POST","/api/uploads/"+task.get("id").getAsString()+"/stream",payload,session,"application/octet-stream"));
        assertEquals("README",stream.get("name").getAsString());assertEquals("application/octet-stream",stream.get("mime").getAsString());
        metadata.addProperty("name","native");metadata.addProperty("restartOnly",false);metadata.addProperty("sha256",sha(payload));
        task=json(send("POST","/api/uploads",metadata.toString().getBytes(StandardCharsets.UTF_8),session,"application/json"));String path="/api/uploads/"+task.get("id").getAsString();
        HttpRequest chunk=HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+session)
                .header("X-Offset","0").header("X-Chunk-SHA256",sha(payload)).PUT(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
        assertEquals(200,client.send(chunk,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        JsonObject complete=json(send("POST",path+"/complete",("{\"sha256\":\""+sha(payload)+"\"}").getBytes(StandardCharsets.UTF_8),session,"application/json"));
        assertEquals("native",complete.get("name").getAsString());assertEquals("application/octet-stream",complete.get("mime").getAsString());
    }

    @Test void asciiDownloadFallbackRetainsOnlySafeExtensionsWithoutHeaderInjection() throws Exception {
        byte[] payload=ascii("payload");
        ShareFile shared=server.share("照片.HEIC",payload.length,"image/heic",offset->new ByteArrayInputStream(payload));
        String header=send("GET","/api/files/"+shared.id,null,session,null).headers().firstValue("content-disposition").orElseThrow();
        assertEquals("attachment; filename=\"download.HEIC\"; filename*=UTF-8''"+encoded("照片.HEIC"),header);
        shared=server.share("x.\r\nX-Injected: yes",payload.length,null,offset->new ByteArrayInputStream(payload));
        HttpResponse<byte[]> response=send("GET","/api/files/"+shared.id,null,session,null);
        header=response.headers().firstValue("content-disposition").orElseThrow();
        assertTrue(header.startsWith("attachment; filename=\"download\";"));assertFalse(header.contains("\r"));assertFalse(header.contains("\n"));
        assertTrue(response.headers().firstValue("x-injected").isEmpty());assertArrayEquals(payload,response.body());
    }
}
