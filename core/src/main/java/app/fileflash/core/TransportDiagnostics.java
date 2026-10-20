package app.fileflash.core;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, process-local timing data. Deliberately never records payloads or identifying headers. */
final class TransportDiagnostics {
    static final int CAPACITY=128;
    private final long origin=System.nanoTime();
    private final AtomicLong sequence=new AtomicLong();
    private final Deque<Trace> recent=new ArrayDeque<>();
    private final Map<String,Long> counters=new LinkedHashMap<>();

    synchronized Trace accepted() {
        Trace trace=new Trace(sequence.incrementAndGet(),origin);
        if(recent.size()==CAPACITY)recent.removeFirst();
        recent.addLast(trace);increment("accepted");return trace;
    }

    synchronized void increment(String key) {counters.put(key,counters.getOrDefault(key,0L)+1);}

    synchronized JsonObject snapshot() {
        JsonObject result=new JsonObject();result.addProperty("capacity",CAPACITY);
        result.addProperty("elapsedMs",millis(System.nanoTime()-origin));
        JsonObject totals=new JsonObject();for(Map.Entry<String,Long> item:counters.entrySet())totals.addProperty(item.getKey(),item.getValue());
        result.add("counts",totals);JsonArray requests=new JsonArray();
        for(Trace trace:recent)if(!trace.omitted)requests.add(trace.snapshot());
        result.add("requests",requests);return result;
    }

    static double millis(long nanos) {return Math.round(nanos/1000.0)/1000.0;}

    static final class Trace {
        final long sequence,origin,acceptedAt=System.nanoTime();
        final Map<String,Double> stages=new LinkedHashMap<>(),durations=new LinkedHashMap<>();
        volatile boolean omitted;
        String method="unknown",route="unknown",platform="unknown",clientRequestId;
        String stage="accepted",transferEncoding="none",expect="none",contentType="missing",errorClass,errorCode;
        long contentLength=-1,receivedBytes,offset=-1;
        int status,headerBytes,prefetchedBytes;
        boolean flushed,closed,hasContentLength;

        Trace(long sequence,long origin) {this.sequence=sequence;this.origin=origin;mark("accepted");}

        synchronized void mark(String name) {stage=name;stages.put(name,millis(System.nanoTime()-origin));}
        synchronized void duration(String name,long started) {durations.put(name,millis(System.nanoTime()-started));}
        synchronized void addDuration(String name,long started) {durations.put(name,durations.getOrDefault(name,0.0)+millis(System.nanoTime()-started));}
        synchronized void received(int count) {if(receivedBytes==0)mark("body-first-byte");receivedBytes+=count;}
        synchronized void error(Throwable error) {
            errorClass=error.getClass().getSimpleName();
            if(error instanceof HttpServer.HttpError)errorCode=((HttpServer.HttpError)error).code;
            mark("error");
        }

        synchronized void headers(String requestMethod,String path,Map<String,String> headers) {
            method=Arrays.asList("GET","POST","PUT","DELETE","HEAD","OPTIONS").contains(requestMethod)?requestMethod:"other";
            if(path.equals("/api/diagnostics")){route="diagnostics";omitted=true;}
            else if(path.equals("/api/diagnostics/probe"))route="diagnostic-probe";
            else if(path.equals("/api/pair"))route="pair";
            else if(path.equals("/api/state"))route="state";
            else if(path.equals("/api/uploads"))route="upload-create";
            else if(path.equals("/api/shortcuts/files"))route="shortcut-upload";
            else if(path.startsWith("/api/uploads/"))route=path.endsWith("/stream")?"upload-stream":path.endsWith("/complete")?"upload-complete":path.endsWith("/prefix")?"upload-prefix":path.endsWith("/chunk")||requestMethod.equals("PUT")?"upload-chunk":"upload-state";
            else if(path.startsWith("/api/files/")||path.startsWith("/api/downloads/"))route="download";
            else if(path.startsWith("/api/"))route="api-other";
            else route="asset";
            String agent=headers.getOrDefault("user-agent","").toLowerCase(Locale.ROOT);
            platform=agent.contains("iphone")||agent.contains("ipad")||agent.contains("ipod")?"ios":agent.contains("android")?"android":agent.contains("windows")||agent.contains("macintosh")||agent.contains("linux")?"desktop":"unknown";
            String id=headers.get("x-request-id");if(id!=null&&id.matches("[0-9a-fA-F-]{8,64}"))clientRequestId=id;
            hasContentLength=headers.containsKey("content-length");
            String value=headers.get("content-length");
            if(value!=null&&value.matches("[0-9]{1,19}"))try{contentLength=Long.parseLong(value);}catch(NumberFormatException ignored){ }
            value=headers.get("x-offset");if(value!=null&&value.matches("[0-9]{1,19}"))try{offset=Long.parseLong(value);}catch(NumberFormatException ignored){ }
            value=headers.get("transfer-encoding");transferEncoding=value==null?"none":value.equalsIgnoreCase("chunked")?"chunked":"other";
            value=headers.get("expect");expect=value==null?"none":value.equalsIgnoreCase("100-continue")?"100-continue":"other";
            value=headers.getOrDefault("content-type","").toLowerCase(Locale.ROOT);
            contentType=value.isEmpty()?"missing":value.startsWith("application/json")?"json":value.startsWith("application/octet-stream")?"octet-stream":value.startsWith("multipart/form-data")?"multipart":"other";
            mark("headers-parsed");
        }

        synchronized JsonObject snapshot() {
            JsonObject out=new JsonObject();out.addProperty("sequence",sequence);
            if(clientRequestId!=null)out.addProperty("clientRequestId",clientRequestId);
            out.addProperty("method",method);out.addProperty("route",route);out.addProperty("platform",platform);out.addProperty("stage",stage);
            out.addProperty("contentLength",contentLength);out.addProperty("hasContentLength",hasContentLength);
            out.addProperty("transferEncoding",transferEncoding);out.addProperty("expect",expect);out.addProperty("contentType",contentType);
            out.addProperty("headerBytes",headerBytes);out.addProperty("prefetchedBytes",prefetchedBytes);out.addProperty("receivedBytes",receivedBytes);
            if(offset>=0)out.addProperty("offset",offset);
            out.addProperty("status",status);out.addProperty("flushed",flushed);out.addProperty("closed",closed);
            if(errorClass!=null)out.addProperty("errorClass",errorClass);if(errorCode!=null)out.addProperty("errorCode",errorCode);
            out.addProperty("ageMs",millis(System.nanoTime()-acceptedAt));
            JsonObject times=new JsonObject();for(Map.Entry<String,Double> item:stages.entrySet())times.addProperty(item.getKey(),item.getValue());out.add("atMs",times);
            JsonObject costs=new JsonObject();for(Map.Entry<String,Double> item:durations.entrySet())costs.addProperty(item.getKey(),item.getValue());out.add("durationMs",costs);
            return out;
        }
    }
}
