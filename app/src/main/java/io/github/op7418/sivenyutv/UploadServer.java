package io.github.op7418.sivenyutv;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 极简HTTP 服务：手机扫码打开页面，把图片/视频直接推到电视。
 *
 * 只做三件事：
 *   GET  /        返回上传页面（需带会话令牌）
 *   POST /upload  请求体是文件原始字节，?name=文件名
 *   POST /cancel  确认指定上传已取消，已完整入库的文件保留
 *
 * 没有用 multipart —— 页面里用 fetch 直接发 File 对象，body 就是原始字节，
 * 服务端只按 Content-Length 流式落盘，不会吃内存。上传完即关闭。
 *
 * <h3>访问控制</h3>
 * 端口只在用户主动打开上传窗口期间监听，同一局域网内的任何设备都能连到，
 * 因此做了三层限制：
 * <ul>
 *   <li><b>会话令牌</b>：每次启动生成随机令牌，页面与上传请求都必须携带，
 *       不带或错误的请求一律拒绝，防止旁人凭空猜到端口来写文件。</li>
 *   <li><b>总量上限</b>：单次会话累计接收量有上限，避免开着窗口时
 *       被同一局域网内的其他设备灌满电视存储。</li>
 *   <li><b>单文件上限</b>：单张图片或单个视频的大小上限。</li>
 * </ul>
 * 令牌只存在于内存中，窗口关闭即失效。
 */
final class UploadServer {
    private static final String TAG = "SivenyuUpload";
    /** 单个文件上限 200MB：足够放 4K 视频，同时不至于被单个请求撑爆存储。 */
    private static final long MAX_BYTES = 200L * 1024L * 1024L;
    /** 单次会话（从打开窗口到关闭）累计接收上限 2GB。 */
    private static final long MAX_SESSION_BYTES = 2L * 1024L * 1024L * 1024L;
    /** 令牌长度（十六进制字符数）。 */
    private static final int TOKEN_LENGTH = 32;
    private static final int MAX_UPLOADS = 256;

    interface Listener {
        /** 收到文件时回调（后台线程），file 已完整写入。 */
        void onFile(File file, boolean video);
    }

    private final int port;
    private final File imageDir;
    private final File videoDir;
    private final Listener listener;

    /** 本次会话的访问令牌，每次 start 重新生成；窗口关闭后失效。 */
    private final String token = newToken();
    /** 本次会话已接收的累计字节数，防止开着窗口时被灌满存储。 */
    private final java.util.concurrent.atomic.AtomicLong received =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile ServerSocket server;
    private Socket activeClient;
    private Thread worker;
    private volatile boolean running;
    private boolean started;
    // Only the single request worker touches these states. Cancel follows xhr.abort().
    private final Map<String, String> uploads = new HashMap<>();

    /** 会话令牌，供调用方拼进二维码地址里。 */
    String token() {
        return token;
    }

    private static String newToken() {
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder out = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            out.append("0123456789abcdef".charAt(random.nextInt(16)));
        }
        return out.toString();
    }

    UploadServer(int port, File imageDir, File videoDir, Listener listener) {
        this.port = port;
        this.imageDir = imageDir;
        this.videoDir = videoDir;
        this.listener = listener;
    }

    synchronized boolean start() {
        if (running) return true;
        if (started) return false; // A new window creates a new instance and token.
        try {
            server = new ServerSocket(port);
            server.setReuseAddress(true);
            running = true;
            started = true;
            final ServerSocket listening = server;
            worker = new Thread(() -> loop(listening), "SivenyuUpload");
            worker.setDaemon(true);
            worker.start();
            Log.i(TAG, "上传服务已启动，端口 " + port);
            return true;
        } catch (IOException failed) {
            Log.w(TAG, "端口 " + port + " 无法监听", failed);
            server = null;
            return false;
        }
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // 关闭时的异常无需处理
        }
        server = null;
        if (activeClient != null) {
            try { activeClient.close(); } catch (IOException ignored) { }
            activeClient = null;
        }
        Log.i(TAG, "上传服务已停止");
    }

    boolean isRunning() {
        return running;
    }

    private void loop(ServerSocket listening) {
        while (running && server == listening) {
            Socket client = null;
            try {
                client = listening.accept();
                synchronized (this) {
                    if (!running || server != listening) break;
                    activeClient = client;
                }
                client.setSoTimeout(60_000);
                handle(client);
            } catch (IOException failed) {
                if (running) Log.w(TAG, "连接处理失败", failed);
            } finally {
                synchronized (this) {
                    if (activeClient == client) activeClient = null;
                }
                if (client != null) {
                    try {
                        client.close();
                    } catch (IOException ignored) {
                        // 忽略
                    }
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        OutputStream out = new BufferedOutputStream(client.getOutputStream());

        String requestLine = readLine(in);
        if (requestLine == null) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return;
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];

        Map<String, String> headers = new HashMap<>();
        String line;
        int guard = 0;
        while ((line = readLine(in)) != null && !line.isEmpty() && guard++ < 80) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }

        String query = "";
        int mark = target.indexOf('?');
        if (mark >= 0) {
            query = target.substring(mark + 1);
            target = target.substring(0, mark);
        }

        if (!"POST".equals(method)) {
            // 页面也要求令牌：没有令牌就返回 403，浏览器地址栏输入 IP 只会看到拒绝提示。
            if (!token.equals(param(query, "t")) && !token.equals(headers.get("x-upload-token"))) {
                writeResponse(out, 403, "text/plain; charset=utf-8",
                        "缺少或错误的会话令牌，请在电视上重新扫码打开页面".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = page().getBytes(StandardCharsets.UTF_8);
            writeResponse(out, 200, "text/html; charset=utf-8", body);
            return;
        }
        if (!"/upload".equals(target) && !"/cancel".equals(target)) {
            writeResponse(out, 404, "text/plain; charset=utf-8",
                    "not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!token.equals(param(query, "t")) && !token.equals(headers.get("x-upload-token"))) {
            Log.w(TAG, "拒绝无令牌的上传请求");
            writeResponse(out, 403, "text/plain; charset=utf-8",
                    "缺少或错误的会话令牌".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String id = param(query, "id");
        if (id == null || !id.matches("[A-Za-z0-9_-]{12,80}")) {
            fail(out, "上传标识无效，请重新扫码");
            return;
        }
        if ("/cancel".equals(target)) {
            if ("complete".equals(uploads.get(id))) {
                writeResponse(out, 409, "text/plain; charset=utf-8",
                        "电视已接收完成，文件已保留".getBytes(StandardCharsets.UTF_8));
            } else if (!uploads.containsKey(id) && uploads.size() >= MAX_UPLOADS) {
                writeResponse(out, 429, "text/plain; charset=utf-8",
                        "本次文件数量已达上限，请重新打开电视上传窗口".getBytes(StandardCharsets.UTF_8));
            } else {
                // A tombstone also rejects an aborted request still waiting in the accept queue.
                uploads.put(id, "cancelled");
                writeResponse(out, 200, "text/plain; charset=utf-8",
                        "已取消上传，未完成文件不会入库".getBytes(StandardCharsets.UTF_8));
            }
            return;
        }
        if (uploads.containsKey(id)) {
            writeResponse(out, 409, "text/plain; charset=utf-8",
                    "该上传已处理或已取消".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (uploads.size() >= MAX_UPLOADS) {
            writeResponse(out, 429, "text/plain; charset=utf-8",
                    "本次文件数量已达上限，请重新打开电视上传窗口".getBytes(StandardCharsets.UTF_8));
            return;
        }
        uploads.put(id, "receiving");
        receive(out, in, headers, param(query, "name"), id);
    }

    private void receive(OutputStream out, InputStream in,
                         Map<String, String> headers, String rawName, String id) throws IOException {
        if (rawName == null || rawName.trim().isEmpty()) {
            fail(out, "缺少文件名");
            return;
        }
        long length;
        try {
            length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
        } catch (NumberFormatException broken) {
            length = -1;
        }
        if (length <= 0) {
            fail(out, "空文件");
            return;
        }
        if (length > MAX_BYTES) {
            fail(out, "单个文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB");
            return;
        }
        // 会话总量：防止端口开着时被同一局域网的设备反复灌入。
        long used = received.get();
        if (used + length > MAX_SESSION_BYTES) {
            fail(out, "本次会话累计已达上限（" + (MAX_SESSION_BYTES / 1024 / 1024) + "MB），"
                    + "请在电视上重新打开上传窗口");
            return;
        }

        String name = sanitize(rawName);
        if (name.isEmpty()) {
            fail(out, "文件名无效");
            return;
        }

        boolean video = isVideo(name);
        File dir = video ? videoDir : imageDir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File stagingDir = new File(dir, ".uploads");
        if (!stagingDir.isDirectory() && !stagingDir.mkdirs()) throw new IOException("无法创建上传暂存目录");
        File partial = File.createTempFile("incoming-", ".part", stagingDir);
        boolean complete = false;
        try {
            long remaining = length;
            try (FileOutputStream stream = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                while (remaining > 0 && running) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("传输中断");
                    stream.write(buffer, 0, read);
                    remaining -= read;
                    received.addAndGet(read);
                }
                if (remaining != 0) throw new IOException("上传已停止");
            }
            File file;
            synchronized (this) {
                if (!running) throw new IOException("上传已停止");
                file = unique(dir, name);
                if (!partial.renameTo(file)) throw new IOException("无法保存上传文件");
                complete = true;
                uploads.put(id, "complete");
            }
            Log.i(TAG, "收到" + (video ? "视频 " : "图片 ") + file.getName() + " " + length + " 字节");
            if (listener != null) listener.onFile(file, video);
            writeResponse(out, 200, "text/plain; charset=utf-8", "ok".getBytes(StandardCharsets.UTF_8));
        } finally {
            if (!complete) {
                uploads.put(id, "failed");
                if (partial.exists() && !partial.delete()) Log.w(TAG, "未能清理上传暂存文件");
            }
        }
    }

    private static void fail(OutputStream out, String message) throws IOException {
        writeResponse(out, 400, "text/plain; charset=utf-8",
                message.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------- 工具

    static String localIp() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            String fallback = null;
            for (NetworkInterface nic : all) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip == null || ip.startsWith("169.254.")) continue;
                    if (ip.startsWith("192.168.") || ip.startsWith("10.")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception | OutOfMemoryError failed) {
            Log.w(TAG, "读取局域网地址失败", failed);
            return null;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int ch;
        boolean any = false;
        while ((ch = in.read()) != -1) {
            any = true;
            if (ch == '\n') break;
            if (ch != '\r') buffer.write(ch);
            if (buffer.size() > 8192) break;
        }
        if (!any && buffer.size() == 0) return null;
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String param(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (!key.equals(pair.substring(0, eq))) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception broken) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    private static String sanitize(String raw) {
        String name = raw;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (name.length() > 90) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 60) + ext;
        }
        return name;
    }

    private static File unique(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 500; i++) {
            file = new File(dir, base + "-" + i + ext);
            if (!file.exists()) return file;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    private static boolean isVideo(String name) {
        return MediaLibrary.isVideo(name);
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body)
            throws IOException {
        String status = code == 200 ? "OK" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : code == 409 ? "Conflict"
                : code == 429 ? "Too Many Requests" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** Phone upload queue with an explicit hold-to-cancel action for each file. */
    private String page() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>上传到电视</title>
                <style>
                *{box-sizing:border-box}body{margin:0;padding:26px 18px 44px;background:#12161c;
                color:#e8ecf2;font-family:-apple-system,'PingFang SC',sans-serif}
                h1{font-size:21px;margin:0 0 8px;font-weight:600}
                .sub{font-size:13px;color:#a5afbd;line-height:1.8;margin:0 0 22px}
                .pick{display:block;padding:20px;border:1px dashed #59677a;border-radius:14px;
                text-align:center;font-size:16px;margin-bottom:16px}
                .pick:active{background:#232c39}input[type=file]{display:none}
                #summary{font-size:12px;color:#9ca9ba;margin:0 0 16px}
                .item{padding:15px;background:#1a2029;border:1px solid #29313e;border-radius:13px;margin:0 0 12px}
                .name{font-size:14px;word-break:break-all;line-height:1.6}
                .meta{font-size:12px;color:#8795a9;margin-top:3px}
                .bar{height:4px;background:#303a48;border-radius:4px;margin:12px 0;overflow:hidden}
                .fill{height:100%;width:0;background:#8dbce7;transition:width .15s}
                .bottom{display:flex;align-items:center;gap:12px;justify-content:space-between}
                .status{font-size:12px;color:#a7b3c3;line-height:1.6;flex:1;word-break:break-word}
                .status.ok{color:#8ed2a6}.status.err{color:#f19c95}
                button{position:relative;overflow:hidden;flex-shrink:0;min-height:42px;padding:10px 14px;
                border:1px solid #6c494b;border-radius:10px;background:#2c2227;color:#efb7b2;
                font:inherit;font-size:12px;touch-action:pan-y;user-select:none;-webkit-user-select:none;
                -webkit-touch-callout:none}button::before{content:'';position:absolute;inset:0;
                background:#ed9c8d26;transform:scaleX(0);transform-origin:left;pointer-events:none}
                button.holding::before{animation:hold .7s linear forwards}
                button:focus-visible{outline:2px solid #b4d6fa;outline-offset:3px}
                button:disabled{opacity:.65}button[hidden]{display:none}
                @keyframes hold{to{transform:scaleX(1)}}
                @media(prefers-reduced-motion:reduce){.fill{transition:none}}
                </style></head><body>
                <h1>上传到电视</h1>
                <p class="sub">手机和电视连接同一个 Wi-Fi，图片存入壁纸库，视频存入屏保素材。<br>
                每次上传一个文件；按住文件旁的「长按取消」约 0.7 秒，可取消发送或等待中的文件。</p>
                <label class="pick" for="files">＋ 选择图片或视频</label>
                <input type="file" id="files" accept="image/*,video/*" multiple>
                <div id="summary" role="status">等待选择文件</div><div id="list"></div>
                <script>
                const token='{{UPLOAD_TOKEN}}',input=document.getElementById('files');
                const list=document.getElementById('list'),summary=document.getElementById('summary');
                const queue=[],tasks=[];let active=null,sequence=0;
                function element(tag,cls,text){
                  const node=document.createElement(tag);node.className=cls;
                  if(text!==undefined)node.textContent=text;return node;
                }
                function updateSummary(){
                  const waiting=tasks.filter(t=>t.state==='queued').length;
                  const done=tasks.filter(t=>t.state==='done').length;
                  const cancelled=tasks.filter(t=>t.state==='cancelled').length;
                  summary.textContent=active
                    ?(active.state==='cancelling'?'正在确认取消':'正在上传')+' · '+waiting+' 个等待'
                    :'已完成 '+done+' 个 · 已取消 '+cancelled+' 个';
                }
                function clearHold(task){
                  if(task.holdTimer)clearTimeout(task.holdTimer);
                  task.holdTimer=null;task.button.classList.remove('holding');
                  if(task.state==='queued'||task.state==='sending')task.button.textContent='长按取消';
                }
                function finish(task,state,text){
                  if(!['queued','sending','cancelling'].includes(task.state))return;
                  task.state=state;clearHold(task);task.button.hidden=true;
                  task.status.className='status'+(state==='done'?' ok':state==='failed'?' err':'');
                  task.status.textContent=text;
                  if(state==='done')task.fill.style.width='100%';
                  task.file=null;task.xhr=null;
                  if(active===task)active=null;
                  updateSummary();pump();
                }
                function confirmCancel(task){
                  if(task.state==='queued'){finish(task,'cancelled','已取消，文件未发送');return;}
                  if(task.state!=='sending')return;
                  task.state='cancelling';clearHold(task);task.button.disabled=true;
                  task.button.textContent='取消中…';task.status.textContent='已停止发送，正在确认…';
                  updateSummary();task.xhr.abort();
                  const request=new XMLHttpRequest();
                  request.open('POST','/cancel?t='+token+'&id='+encodeURIComponent(task.id));
                  request.timeout=15000;
                  request.onload=()=>{
                    if(request.status===200)finish(task,'cancelled','已取消，未完成文件不会入库');
                    else if(request.status===409)finish(task,'done','电视已接收完成，文件已保留');
                    else finish(task,'failed','已停止发送；取消结果未确认，请检查电视');
                  };
                  request.onerror=request.ontimeout=()=>finish(task,'failed',
                    '已停止发送；取消结果未确认，请检查电视');
                  request.send();
                }
                function startHold(task,x,y){
                  if(!['queued','sending'].includes(task.state))return;
                  clearHold(task);task.startX=x;task.startY=y;
                  task.button.textContent='继续按住…';task.button.classList.add('holding');
                  task.holdTimer=setTimeout(()=>confirmCancel(task),700);
                }
                function bindHold(task){
                  const button=task.button;
                  button.addEventListener('pointerdown',event=>{
                    if(!event.isPrimary||event.button!==0)return;
                    startHold(task,event.clientX,event.clientY);
                    try{button.setPointerCapture(event.pointerId);}catch(ignore){}
                  });
                  button.addEventListener('pointermove',event=>{
                    if(task.holdTimer&&Math.hypot(event.clientX-task.startX,event.clientY-task.startY)>12)
                      clearHold(task);
                  });
                  for(const type of ['pointerup','pointercancel','lostpointercapture'])
                    button.addEventListener(type,()=>clearHold(task));
                  button.addEventListener('contextmenu',event=>event.preventDefault());
                  button.addEventListener('click',event=>{
                    event.preventDefault();
                    if(['queued','sending'].includes(task.state))task.status.textContent='请按住按钮约 0.7 秒取消';
                  });
                  button.addEventListener('keydown',event=>{
                    if(event.key==='Enter'||event.key===' '){
                      event.preventDefault();if(!event.repeat)startHold(task,0,0);
                    }
                  });
                  button.addEventListener('keyup',event=>{
                    if(event.key==='Enter'||event.key===' '){event.preventDefault();clearHold(task);}
                  });
                  button.addEventListener('blur',()=>clearHold(task));
                }
                function enqueue(file){
                  const row=element('div','item'),name=element('div','name',file.name);
                  const meta=element('div','meta',(file.size/1024/1024).toFixed(1)+' MB');
                  const bar=element('div','bar'),fill=element('div','fill'),bottom=element('div','bottom');
                  const status=element('div','status','等待上传'),button=element('button','','长按取消');
                  status.setAttribute('role','status');button.type='button';
                  button.setAttribute('aria-label','按住约0.7秒取消上传 '+file.name);
                  bar.appendChild(fill);bottom.append(status,button);row.append(name,meta,bar,bottom);
                  list.appendChild(row);
                  const task={id:Date.now().toString(36)+'-'+Math.random().toString(36).slice(2)+'-'+(++sequence),
                    file,state:'queued',row,fill,status,button,holdTimer:null,xhr:null};
                  bindHold(task);tasks.push(task);queue.push(task);
                }
                function pump(){
                  if(active)return;
                  let task=null;
                  while(queue.length){const candidate=queue.shift();if(candidate.state==='queued'){task=candidate;break;}}
                  if(!task){updateSummary();return;}
                  active=task;task.state='sending';task.status.textContent='正在发送 0%';
                  const request=task.xhr=new XMLHttpRequest();
                  request.open('POST','/upload?t='+token+'&id='+encodeURIComponent(task.id)
                    +'&name='+encodeURIComponent(task.file.name));
                  request.upload.onprogress=event=>{
                    if(task.state!=='sending'||!event.lengthComputable)return;
                    const percent=Math.round(event.loaded/event.total*100);
                    task.fill.style.width=percent+'%';
                    task.status.textContent=percent===100?'电视正在保存…':'正在发送 '+percent+'%';
                  };
                  request.onload=()=>{
                    if(task.state!=='sending')return;
                    if(request.status===200)finish(task,'done','完成');
                    else finish(task,'failed','失败：'+(request.responseText||'电视未接收'));
                  };
                  request.onerror=request.ontimeout=request.onabort=()=>{
                    if(task.state==='sending')finish(task,'failed','网络中断，请确认手机与电视仍在同一 Wi-Fi');
                  };
                  updateSummary();request.send(task.file);
                }
                input.addEventListener('change',()=>{
                  for(const file of input.files)enqueue(file);
                  input.value='';pump();
                });
                document.addEventListener('visibilitychange',()=>{
                  if(document.hidden)tasks.forEach(clearHold);
                });
                window.addEventListener('blur',()=>tasks.forEach(clearHold));
                </script></body></html>
                """.replace("{{UPLOAD_TOKEN}}", token);
    }
}
