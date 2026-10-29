package app.fileflash.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ThumbnailTest {
    @TempDir Path directory;
    TransferServer server;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    String base,session,clientId;
    static final byte[] JPEG={(byte)255,(byte)216,1,2,3,(byte)255,(byte)217};

    @BeforeEach void start() throws Exception {
        server=new TransferServer(directory.toFile(),path->new ByteArrayInputStream("<svg/>".getBytes(StandardCharsets.UTF_8)));
        server.start(0);base="http://127.0.0.1:"+server.getPort();
        HttpResponse<byte[]> pairing=send("POST","/api/pair",("{\"token\":\""+server.getPairToken()+"\"}").getBytes(StandardCharsets.UTF_8),false);
        assertEquals(201,pairing.statusCode(),new String(pairing.body(),StandardCharsets.UTF_8));
        JsonObject paired=json(pairing);
        session=paired.get("session").getAsString();clientId=paired.get("clientId").getAsString();
    }
    @AfterEach void stop(){server.stop();}
    HttpResponse<byte[]> send(String method,String path,byte[] body,boolean auth)throws Exception {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(4));
        if(auth)request.header("Authorization","Bearer "+session);
        if(body!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static JsonObject json(HttpResponse<byte[]> response){return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
    ShareFile share(String name,String mime){return server.share(name,32,mime,offset->new ByteArrayInputStream(new byte[32]));}
    String path(ShareFile file){return "/api/files/"+file.id+"/thumbnail";}

    @Test void classificationUsesExtensionsWhenDevicesSendGenericMime() {
        for(String name:Arrays.asList("photo.HEIC","photo.JPG","photo.avif"))assertEquals(FileKind.IMAGE,FileKind.classify(name,"application/octet-stream"));
        for(String name:Arrays.asList("clip.MOV","clip.mp4","clip.mkv"))assertEquals(FileKind.VIDEO,FileKind.classify(name,null));
        for(String name:Arrays.asList("notes.PDF","notes.xml","notes.txt","notes.docx","notes.json"))assertEquals(FileKind.DOCUMENT,FileKind.classify(name,"application/octet-stream"));
        for(String name:Arrays.asList("music.MP3","music.wav","music.ncm"))assertEquals(FileKind.AUDIO,FileKind.classify(name,"application/octet-stream"));
        assertEquals(FileKind.OTHER,FileKind.classify("package.apk","application/vnd.android.package-archive"));
        assertEquals(FileKind.OTHER,FileKind.classify(null,null));
        assertEquals(FileKind.IMAGE,FileKind.classify("photo","IMAGE/JPEG; charset=binary"));
        assertEquals(FileKind.VIDEO,FileKind.classify("clip","video/quicktime"));
        assertEquals(FileKind.DOCUMENT,FileKind.classify("document","application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals(FileKind.AUDIO,FileKind.classify("music","audio/mpeg"));
        assertFalse(FileKind.DOCUMENT.isMedia());assertTrue(FileKind.VIDEO.isMedia());
    }

    @Test void requiresAuthenticationAndExactGetRouteBeforeInvokingProvider()throws Exception {
        ShareFile file=share("photo.jpg","image/jpeg");AtomicInteger calls=new AtomicInteger();
        server.setThumbnailProvider(f->{calls.incrementAndGet();return JPEG;});
        assertEquals(401,send("GET",path(file),null,false).statusCode());
        assertEquals(405,send("POST",path(file),null,true).statusCode());
        assertEquals(405,send("HEAD",path(file),null,true).statusCode());
        assertEquals(404,send("GET",path(file)+"/extra",null,true).statusCode());
        assertEquals(404,send("GET",path(file)+"/",null,true).statusCode());
        assertEquals(404,send("GET","/api/files/missing/thumbnail",null,true).statusCode());
        assertEquals(0,calls.get());
    }

    @Test void servesBoundedJpegWithoutDownloadTasksOrPersistentFiles()throws Exception {
        ShareFile file=share("photo.jpg","image/jpeg");server.setThumbnailProvider(f->{assertEquals(file.id,f.id);return JPEG;});
        HttpResponse<byte[]> result=send("GET",path(file),null,true);
        assertEquals(200,result.statusCode());assertArrayEquals(JPEG,result.body());
        assertEquals("image/jpeg",result.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(0,json(send("GET","/api/state",null,true)).getAsJsonArray("downloads").size());
        try(java.util.stream.Stream<Path> paths=Files.walk(directory)){assertEquals(0,paths.filter(Files::isRegularFile).count());}
        assertArrayEquals(JPEG,server.thumbnail(file.id));
    }

    @Test void unavailableOrInvalidPreviewsFallBackWithoutReadingOtherFileTypes()throws Exception {
        ShareFile image=share("photo.jpg","image/jpeg"),document=share("note.pdf","application/pdf");
        assertEquals(204,send("GET",path(image),null,true).statusCode());
        AtomicInteger calls=new AtomicInteger();server.setThumbnailProvider(f->{calls.incrementAndGet();return JPEG;});
        assertEquals(204,send("GET",path(document),null,true).statusCode());assertEquals(0,calls.get());
        server.setThumbnailProvider(f->{throw new IOException("unsupported codec");});
        assertEquals(204,send("GET",path(image),null,true).statusCode());
        for(byte[] invalid:Arrays.asList(new byte[0],new byte[]{1,2,3,4},new byte[TransferServer.MAX_THUMBNAIL_BYTES+1])) {
            server.setThumbnailProvider(f->invalid);assertEquals(204,send("GET",path(image),null,true).statusCode());
        }
        byte[] maximum=new byte[TransferServer.MAX_THUMBNAIL_BYTES];maximum[0]=(byte)255;maximum[1]=(byte)216;maximum[maximum.length-2]=(byte)255;maximum[maximum.length-1]=(byte)217;
        server.setThumbnailProvider(f->maximum);assertArrayEquals(maximum,send("GET",path(image),null,true).body());
    }

    @Test void onlyOneDecoderRunsWhileStateRemainsResponsive()throws Exception {
        ShareFile file=share("clip.mov","video/quicktime");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        server.setThumbnailProvider(f->{entered.countDown();await(release);return JPEG;});
        CompletableFuture<HttpResponse<byte[]>> first=CompletableFuture.supplyAsync(()->{try{return send("GET",path(file),null,true);}catch(Exception e){throw new CompletionException(e);}});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(429,send("GET",path(file),null,true).statusCode());
            assertEquals(200,send("GET","/api/state",null,true).statusCode());
        } finally {release.countDown();}
        assertEquals(200,first.get(3,TimeUnit.SECONDS).statusCode());
        assertEquals(200,send("GET",path(file),null,true).statusCode());
    }

    @Test void revokedSessionNeverReceivesGeneratedPreview()throws Exception {
        ShareFile file=share("photo.jpg","image/jpeg");server.setThumbnailProvider(f->{server.revokeClient(clientId);return JPEG;});
        try {assertNotEquals(200,send("GET",path(file),null,true).statusCode());}catch(IOException expected){/* revocation also closes the in-flight socket */}
        assertEquals(401,send("GET",path(file),null,true).statusCode());
    }

    @Test void sourceRemovalWhileDecodingDoesNotReturnStaleBytes()throws Exception {
        ShareFile file=share("photo.jpg","image/jpeg");server.setThumbnailProvider(f->{server.removeFile(f.id);return JPEG;});
        assertEquals(404,send("GET",path(file),null,true).statusCode());
    }

    @Test void receivedPreviewPinsBytesWithoutBlockingStateOrExtendingIdleAge()throws Exception {
        byte[] data={1,2,3};
        JsonObject upload=json(send("POST","/api/uploads","{\"name\":\"photo.jpg\",\"size\":3,\"restartOnly\":true}".getBytes(StandardCharsets.UTF_8),true));
        String id=upload.get("id").getAsString();
        HttpRequest uploadRequest=HttpRequest.newBuilder(URI.create(base+"/api/uploads/"+id+"/stream")).header("Authorization","Bearer "+session).header("X-Upload-Key",upload.get("resumeKey").getAsString()).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();
        assertEquals(200,client.send(uploadRequest,HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        Path metadata=directory.resolve("uploads").resolve(id+".json");byte[] before=Files.readAllBytes(metadata);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        server.setThumbnailProvider(f->{assertArrayEquals(data,Files.readAllBytes(server.receivedFile(id).toPath()));entered.countDown();await(release);return JPEG;});
        CompletableFuture<byte[]> preview=CompletableFuture.supplyAsync(()->{try{return server.thumbnail(id);}catch(IOException e){throw new CompletionException(e);}});
        try {
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertThrows(IOException.class,()->server.removeReceivedFile(id));
            assertThrows(IOException.class,()->server.releaseReceivedCache(id,offset->new ByteArrayInputStream(data)));
            assertEquals(200,send("GET","/api/state",null,true).statusCode());
            assertNotNull(server.receivedFile(id));
        } finally {release.countDown();}
        assertArrayEquals(JPEG,preview.get(3,TimeUnit.SECONDS));assertArrayEquals(before,Files.readAllBytes(metadata));
        server.releaseReceivedCache(id,offset->new ByteArrayInputStream(data));assertNull(server.receivedFile(id));
    }

    @Test void servesOnlyTheThreeBundledSvgPathsAndPermitsBlobImages()throws Exception {
        for(String name:Arrays.asList("documents-folder.svg","audio-eighth-note.svg","unknown-file.svg")) {
            HttpResponse<byte[]> result=send("GET","/file-icons/"+name,null,false);assertEquals(200,result.statusCode());assertEquals("image/svg+xml",result.headers().firstValue("Content-Type").orElseThrow());
        }
        assertEquals(404,send("GET","/file-icons/private.svg",null,false).statusCode());
        assertTrue(send("GET","/",null,false).headers().firstValue("Content-Security-Policy").orElseThrow().contains("img-src 'self' data: blob:"));
    }

    private static void await(CountDownLatch latch)throws IOException {
        try {if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Test latch timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
    }
}
