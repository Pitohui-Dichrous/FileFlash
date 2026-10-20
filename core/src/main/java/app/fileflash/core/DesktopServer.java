package app.fileflash.core;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Local integration harness; it deliberately prints a pairing credential to its local console. */
public final class DesktopServer {
    public static void main(String[] args) throws Exception {
        File storage=new File(".test-storage"),assets=new File("app/src/main/assets/web"),ready=null;
        int port=8787;List<File> shares=new ArrayList<>();
        for(int i=0;i<args.length;i++) {
            switch(args[i]) {
                case "--storage":storage=new File(args[++i]);break;
                case "--assets":assets=new File(args[++i]);break;
                case "--port":port=Integer.parseInt(args[++i]);break;
                case "--ready":ready=new File(args[++i]);break;
                case "--share":shares.add(new File(args[++i]));break;
                default:throw new IllegalArgumentException("Arguments: --storage DIR --assets DIR --port NUMBER --ready FILE --share FILE");
            }
        }
        File web=assets.getCanonicalFile();
        TransferServer server=new TransferServer(storage,path->{File file=new File(web,path).getCanonicalFile();if(!file.toPath().startsWith(web.toPath())||!file.isFile())return null;return new FileInputStream(file);});
        for(File file:shares)server.share(file);
        server.start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop,"fileflash-shutdown"));
        JsonObject info=new JsonObject();info.addProperty("port",server.getPort());info.addProperty("pairToken",server.getPairToken());
        info.addProperty("url","http://127.0.0.1:"+server.getPort());info.addProperty("storage",storage.getCanonicalPath());
        String json=info.toString();
        if(ready!=null)try(OutputStream output=new FileOutputStream(ready)){output.write(json.getBytes(StandardCharsets.UTF_8));}
        System.out.println(json);System.out.flush();
        new CountDownLatch(1).await();
    }
}
