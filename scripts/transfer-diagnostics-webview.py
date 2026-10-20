"""Dedicated-emulator-only WebView harness; never changes app.fileflash.

Set FILEFLASH_DEDICATED_EMULATOR=1, JAVA_HOME and ANDROID_HOME, then run:
  python scripts/transfer-diagnostics-webview.py emulator-5554 ready.json report.json
Use --build-only to prepare its separate test APK without any ADB operation.
The test app receives a synthetic 60 MiB content URI through its file chooser.
"""
import argparse
import os
import pathlib
import re
import subprocess
import time
import urllib.parse
import json
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACKAGE = 'app.fileflash.testshell'
MAIN = r'''
package app.fileflash.testshell;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.net.Uri;
import java.io.*;
public final class MainActivity extends Activity {
  private WebView view;
  @Override public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(128);
    try {
      File source=new File(getFilesDir(),"benchmark.bin");
      if(source.length()!=60L*1024*1024) {
        byte[] block=new byte[64*1024];for(int i=0;i<block.length;i++)block[i]=(byte)i;
        try(FileOutputStream out=new FileOutputStream(source)){for(int i=0;i<960;i++)out.write(block);}
      }
    }catch(IOException error){throw new RuntimeException(error);}
    if(getIntent().getBooleanExtra("native",false)) {
      new Thread(()->{NativeRunner.run(new File(getFilesDir(),"benchmark.bin"),getIntent().getDataString(),new File(getFilesDir(),"native-result.json"));runOnUiThread(()->finish());},"controlled-native-upload").start();return;
    }
    WebView.setWebContentsDebuggingEnabled(true);
    view=new WebView(this);setContentView(view);
    view.getSettings().setJavaScriptEnabled(true);view.getSettings().setDomStorageEnabled(true);
    view.setWebViewClient(new WebViewClient());
    view.setWebChromeClient(new WebChromeClient(){
      @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
        callback.onReceiveValue(new Uri[]{Uri.parse("content://app.fileflash.testshell.source/benchmark")});return true;
      }
    });
    String url=getIntent().getDataString();view.loadUrl(url==null?"about:blank":url);
  }
  @Override public void onDestroy(){if(view!=null)view.destroy();super.onDestroy();}
}
'''
PROVIDER = r'''
package app.fileflash.testshell;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
public final class SourceProvider extends ContentProvider {
  @Override public boolean onCreate(){return true;}
  private File file(Uri uri)throws FileNotFoundException{
    if(!"/benchmark".equals(uri.getPath()))throw new FileNotFoundException();return new File(getContext().getFilesDir(),"benchmark.bin");
  }
  @Override public String getType(Uri uri){return "application/octet-stream";}
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
    if(!"r".equals(mode))throw new FileNotFoundException();return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY);
  }
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
    MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});
    cursor.addRow(new Object[]{"controlled-android-webview-60MiB.bin",60L*1024*1024});return cursor;
  }
  @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
  @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
  @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
'''
NATIVE = r'''
package app.fileflash.testshell;
import java.io.*;
import java.net.*;
import java.security.*;
import org.json.*;
final class NativeRunner {
  private static double now(){return System.nanoTime()/1000000.0;}
  private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString();}
  private static byte[] json(JSONObject body)throws Exception{return body.toString().getBytes("UTF-8");}
  private static JSONObject request(String base,String method,String path,String session,byte[] data,String key,long offset,String sha)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
    String requestId=java.util.UUID.randomUUID().toString();c.setRequestProperty("X-Request-ID",requestId);
    c.setRequestMethod(method);c.setConnectTimeout(12000);c.setReadTimeout(45000);c.setUseCaches(false);c.setInstanceFollowRedirects(false);
    if(session!=null)c.setRequestProperty("Authorization","Bearer "+session);
    if(key!=null)c.setRequestProperty("X-Upload-Key",key);
    if(offset>=0)c.setRequestProperty("X-Offset",String.valueOf(offset));
    if(sha!=null)c.setRequestProperty("X-Chunk-SHA256",sha);
    try{
      if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type",offset>=0?"application/octet-stream":"application/json");c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}}
      int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("Controlled HTTP request returned "+status);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=c.getInputStream()){byte[] buf=new byte[8192];int count;while((count=input.read(buf))>=0){if(bytes.size()+count>131072)throw new IOException("Response bound exceeded");bytes.write(buf,0,count);}}
      return new JSONObject(bytes.toString("UTF-8")).put("_controlledRequestId",requestId);
    }finally{c.disconnect();}
  }
  static void run(File file,String link,File output){JSONObject report=new JSONObject();
    try{
      int fragment=link.indexOf('#');String base=link.substring(0,fragment);if(base.endsWith("/"))base=base.substring(0,base.length()-1);
      String token=URLDecoder.decode(link.substring(fragment+6),"UTF-8");
      String session=request(base,"POST","/api/pair",null,json(new JSONObject().put("token",token).put("name","Controlled Android HTTP fixture")),null,-1,null).getString("session");
      JSONObject task=request(base,"POST","/api/uploads",session,json(new JSONObject().put("name","controlled-android-native-60MiB.bin").put("size",file.length()).put("mime","application/octet-stream")),null,-1,null);
      String id=task.getString("id"),key=task.getString("resumeKey");JSONArray puts=new JSONArray();MessageDigest full=MessageDigest.getInstance("SHA-256");
      double started=now(),lastAck=started,readTotal=0,hashTotal=0,streamHashTotal=0;
      try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[2*1024*1024];long offset=0;
        while(offset<file.length()){
          double at=now();int count=0,wanted=(int)Math.min(buffer.length,file.length()-offset);while(count<wanted){int n=input.read(buffer,count,wanted-count);if(n<0)throw new IOException("Synthetic source truncated");count+=n;}double readMs=now()-at;readTotal+=readMs;
          byte[] block=count==buffer.length?buffer:java.util.Arrays.copyOf(buffer,count);at=now();String sha=hex(MessageDigest.getInstance("SHA-256").digest(block));double hashMs=now()-at;hashTotal+=hashMs;
          double sent=now();JSONObject ack=request(base,"PUT","/api/uploads/"+id,session,block,key,offset,sha);double done=now();if(ack.getLong("offset")!=offset+count)throw new IOException("Unexpected committed offset");
          at=now();full.update(block);streamHashTotal+=now()-at;puts.put(new JSONObject().put("requestId",ack.getString("_controlledRequestId")).put("offset",offset).put("bodyBytes",count).put("readMs",readMs).put("chunkHashMs",hashMs).put("betweenPreviousAckAndSendMs",sent-lastAck).put("requestMs",done-sent).put("ackGapMs",done-lastAck));lastAck=done;offset+=count;
        }
      }
      double uploadMs=now()-started;String sha=hex(full.digest());double at=now();JSONObject complete=request(base,"POST","/api/uploads/"+id+"/complete",session,json(new JSONObject().put("sha256",sha)),key,-1,null);
      if(!sha.equals(complete.getString("sha256")))throw new IOException("Final SHA mismatch");
      report.put("passed",true).put("scope","Actual Android HttpURLConnection and SHA-256 in a separate dedicated-emulator test app, same-emulator loopback to signed FileFlash; not personal phones, Wi-Fi or production NativeClient UI.")
        .put("fileBytes",file.length()).put("sha256",sha).put("uploadMs",uploadMs).put("completeMs",now()-at).put("totalMs",now()-started).put("readMs",readTotal).put("chunkHashMs",hashTotal).put("streamHashMs",streamHashTotal).put("puts",puts);
      try{report.put("coreDiagnostics",request(base,"GET","/api/diagnostics",session,null,null,-1,null));}catch(IOException ignored){}
    }catch(Exception error){try{report.put("passed",false).put("errorClass",error.getClass().getSimpleName()).put("error",error.getMessage());}catch(Exception ignored){}}
    try(FileOutputStream out=new FileOutputStream(output)){out.write(report.toString().getBytes("UTF-8"));}catch(Exception ignored){}
  }
}
'''
MANIFEST = '''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="app.fileflash.testshell">
<uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>
<uses-permission android:name="android.permission.INTERNET"/>
<application android:label="Transfer test" android:debuggable="true" android:usesCleartextTraffic="true" android:allowBackup="false">
<activity android:name=".MainActivity" android:exported="true"/>
<provider android:name=".SourceProvider" android:authorities="app.fileflash.testshell.source" android:exported="false" android:grantUriPermissions="true"/>
</application></manifest>'''


def run(args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, text=True, **kwargs).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('serial')
    parser.add_argument('ready', nargs='?')
    parser.add_argument('report', nargs='?')
    parser.add_argument('--build-only', action='store_true')
    parser.add_argument('--native-only', action='store_true', help='Run actual Android HttpURLConnection instead of WebView.')
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', args.serial):
        raise RuntimeError('Only an explicitly named dedicated emulator is accepted.')
    sdk = pathlib.Path(os.environ.get('ANDROID_HOME', ROOT / '.tools/android-sdk'))
    java = pathlib.Path(os.environ.get('JAVA_HOME', r'C:\Program Files\Java\jdk-19')) / 'bin'
    tools = sdk / 'build-tools/37.0.0'
    android = sdk / 'platforms/android-29/android.jar'
    work = ROOT / '.test-data' / ('transfer-webview-' + args.serial)
    work.mkdir(parents=True, exist_ok=True)
    for directory in ['source', 'classes', 'dex']:
        (work / directory).mkdir(exist_ok=True)
    (work / 'source/MainActivity.java').write_text(MAIN, encoding='utf-8')
    (work / 'source/SourceProvider.java').write_text(PROVIDER, encoding='utf-8')
    (work / 'source/NativeRunner.java').write_text(NATIVE, encoding='utf-8')
    (work / 'AndroidManifest.xml').write_text(MANIFEST, encoding='utf-8')
    run([java / 'javac.exe', '-source', '8', '-target', '8', '-classpath', android, '-d', work / 'classes',
         work / 'source/MainActivity.java', work / 'source/SourceProvider.java', work / 'source/NativeRunner.java'])
    classes = sorted((work / 'classes').rglob('*.class'))
    run([java / 'java.exe', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--lib', android,
         '--min-api', '29', '--output', work / 'dex', *classes])
    run([tools / 'aapt2.exe', 'link', '-o', work / 'unsigned.apk', '--manifest', work / 'AndroidManifest.xml',
         '-I', android])
    with zipfile.ZipFile(work / 'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(work / 'dex/classes.dex', 'classes.dex')
    key = work / 'testshell.p12'
    if not key.exists():
        run([java / 'keytool.exe', '-genkeypair', '-keystore', key, '-storepass', 'android', '-keypass', 'android',
             '-alias', 'androiddebugkey', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
             '-dname', 'CN=Dedicated emulator transport test'])
    run([java / 'java.exe', '-jar', tools / 'lib/apksigner.jar', 'sign', '--ks', key,
         '--ks-pass', 'pass:android', '--out', work / 'testshell.apk', work / 'unsigned.apk'])
    if args.build_only:
        print('Prepared separate dedicated-emulator WebView test APK; no ADB operation.')
        return
    if os.environ.get('FILEFLASH_DEDICATED_EMULATOR') != '1':
        raise RuntimeError('Explicit FILEFLASH_DEDICATED_EMULATOR=1 is required for ADB operations.')
    if not args.ready or not args.report:
        raise RuntimeError('Private ready file and report path are required.')
    adb = sdk / 'platform-tools/adb.exe'
    def device(*command):
        return run([adb, '-s', args.serial, *command])
    if device('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise RuntimeError('Selected device is not an emulator.')
    device('install', '-r', str(work / 'testshell.apk'))
    package_info = device('shell', 'dumpsys', 'package', 'app.fileflash')
    package_name = re.search(r'\bversionName=(\S+)', package_info).group(1)
    package_path = device('shell', 'pm', 'path', 'app.fileflash').splitlines()[0].split(':', 1)[1]
    metadata = {'api': int(device('shell', 'getprop', 'ro.build.version.sdk')), 'version': package_name, 'serverVersion': package_name,
                'serverApkSha256': device('shell', 'sha256sum', package_path).split()[0]}
    ready = json.loads(pathlib.Path(args.ready).read_text(encoding='utf-8'))
    parsed = urllib.parse.urlsplit(ready['url'])
    host_port = parsed.port or 80
    url = 'http://127.0.0.1:' + str(host_port) + '/#pair=' + urllib.parse.quote(ready['pairToken'], safe='')
    device('shell', 'am', 'force-stop', PACKAGE)
    if args.native_only:
        device('shell', 'rm', '-f', '/data/user/0/' + PACKAGE + '/files/native-result.json')
        device('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', '-d', url, '--ez', 'native', 'true')
        for _ in range(180):
            answer = subprocess.run([str(adb), '-s', args.serial, 'exec-out', 'cat', '/data/user/0/' + PACKAGE + '/files/native-result.json'], capture_output=True)
            if answer.returncode == 0:
                try:
                    report = json.loads(answer.stdout)
                except json.JSONDecodeError:
                    time.sleep(.1)
                    continue
                if not report.get('passed'):
                    raise RuntimeError('Controlled Android native upload failed: ' + report.get('errorClass', 'unknown'))
                report.update(metadata)
                report['terminalResponseIncluded'] = True
                report['transferToCompletionMs'] = report['totalMs']
                summarize_core(report)
                pathlib.Path(args.report).write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
                print('PASS actual Android HttpURLConnection: 60 MiB; ' + str(round(report['totalMs'], 2)) + ' ms.')
                return
            time.sleep(1)
        raise RuntimeError('Controlled Android native upload timed out.')
    device('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity', '-d', url)
    pid = device('shell', 'pidof', PACKAGE).split()[0]
    device('forward', 'tcp:9222', 'localabstract:webview_devtools_remote_' + pid)
    for attempt in range(20):
        try:
            run(['node', str(ROOT / 'scripts/transfer-diagnostics-webview.cjs'), args.ready, args.report,
                 'http://127.0.0.1:9222'])
            report = json.loads(pathlib.Path(args.report).read_text(encoding='utf-8'))
            report.update(metadata)
            summarize_core(report)
            pathlib.Path(args.report).write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
            print('PASS dedicated Android WebView upload. Report contains no pairing credentials.')
            break
        except subprocess.CalledProcessError:
            if attempt != 0:
                raise
            # The WebView process may publish its debugging socket just after activity start.
            time.sleep(2)
    device('forward', '--remove', 'tcp:9222')


def summarize_core(report):
    snapshot = report.pop('coreDiagnostics', None)
    if not snapshot:
        return
    ids = {item['requestId'] for item in report['puts'] if item.get('requestId')}
    traces = [item for item in snapshot['transport']['requests'] if item.get('clientRequestId') in ids]
    if len(traces) != len(report['puts']):
        raise RuntimeError('Candidate transport diagnostics did not retain every benchmark chunk.')
    fields = sorted({key for item in traces for key in item.get('durationMs', {})})
    values = {field: [item.get('durationMs', {}).get(field, 0) for item in traces] for field in fields}
    for field, end, start in [('workerQueue', 'worker-started', 'worker-queued'),
                              ('parsedHeaderToFirstBodyByte', 'body-first-byte', 'headers-parsed'),
                              ('acceptedToFlushed', 'response-flushed', 'accepted')]:
        values[field] = [item['atMs'][end] - item['atMs'][start] for item in traces]
    report['coreTimingMs'] = {field: {'average': round(sum(numbers) / len(numbers), 3),
                                    'maximum': round(max(numbers), 3), 'total': round(sum(numbers), 3)}
                              for field, numbers in values.items()}
    report['coreChunkTraceCount'] = len(traces)
    report['coreChunkTraces'] = traces


if __name__ == '__main__':
    main()
