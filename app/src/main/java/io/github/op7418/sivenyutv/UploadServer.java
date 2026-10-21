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
 *   POST /clear   清空刚上传的暂存（保留给以后扩展）
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

    private ServerSocket server;
    private Thread worker;
    private volatile boolean running;

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

    boolean start() {
        if (running) return true;
        try {
            server = new ServerSocket(port);
            server.setReuseAddress(true);
            running = true;
            worker = new Thread(this::loop, "SivenyuUpload");
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

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // 关闭时的异常无需处理
        }
        server = null;
        worker = null;
        Log.i(TAG, "上传服务已停止");
    }

    boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            Socket client = null;
            try {
                client = server.accept();
                client.setSoTimeout(60_000);
                handle(client);
            } catch (IOException failed) {
                if (running) Log.w(TAG, "连接处理失败", failed);
            } finally {
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
        if (!"/upload".equals(target)) {
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
        receive(out, in, headers, param(query, "name"));
    }

    private void receive(OutputStream out, InputStream in,
                         Map<String, String> headers, String rawName) throws IOException {
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
        File file = unique(dir, name);

        long remaining = length;
        try (FileOutputStream stream = new FileOutputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int read = in.read(buffer, 0, want);
                if (read < 0) break;
                stream.write(buffer, 0, read);
                remaining -= read;
                // 按实际写入量累加，而不是按声明的Content-Length，
                // 避免伪造长度绕过会话总量上限。
                received.addAndGet(read);
            }
        }

        if (remaining > 0) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            fail(out, "传输中断");
            return;
        }

        Log.i(TAG, "收到" + (video ? "视频 " : "图片 ") + file.getName()
                + " " + length + " 字节 -> " + file.getAbsolutePath());
        if (listener != null) listener.onFile(file, video);
        writeResponse(out, 200, "text/plain; charset=utf-8",
                "ok".getBytes(StandardCharsets.UTF_8));
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
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".avi");
    }

    private static void writeResponse(OutputStream out, int code, String type, byte[] body)
            throws IOException {
        String status = code == 200 ? "OK" : (code == 404 ? "Not Found" : "Bad Request");
        String head = "HTTP/1.1 " + code + " " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    /** 手机端页面：深色、单列、带每个文件的进度条。令牌由服务端注入到上传请求里。 */
    private String page() {
        final String tk = token;
        StringBuilder html = new StringBuilder(2600);
        html.append("<!DOCTYPE html><html lang='zh-CN'><head><meta charset='utf-8'>");
        html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<title>上传到电视</title><style>");
        html.append("*{box-sizing:border-box}");
        html.append("body{margin:0;padding:26px 18px 44px;background:#12161C;color:#E8ECF2;");
        html.append("font-family:-apple-system,'PingFang SC','Noto Sans CJK SC',sans-serif}");
        html.append("h1{font-size:21px;margin:0 0 8px;font-weight:600}");
        html.append("p.sub{font-size:13px;color:#8A94A6;margin:0 0 22px;line-height:1.7}");
        html.append("label.pick{display:block;padding:20px;border:1px dashed #3A4553;");
        html.append("border-radius:14px;text-align:center;color:#AAB4C4;font-size:16px;margin-bottom:20px}");
        html.append("label.pick:active{border-color:#F1683A;color:#F1683A}");
        html.append("input[type=file]{display:none}");
        html.append(".item{padding:13px 15px;background:#1A2029;border-radius:11px;");
        html.append("margin-bottom:11px;font-size:14px;word-break:break-all}");
        html.append(".bar{height:4px;background:#2A323D;border-radius:2px;margin-top:9px;overflow:hidden}");
        html.append(".fill{height:100%;width:0;background:#F1683A;transition:width .2s}");
        html.append(".ok{color:#5FBF7F;font-size:13px;margin-top:7px}");
        html.append(".err{color:#E86A62;font-size:13px;margin-top:7px}");
        html.append("</style></head><body>");
        html.append("<h1>上传到电视</h1>");
        html.append("<p class='sub'>选图片存进壁纸库，选视频存进屏保素材，都可以多选。<br>");
        html.append("手机和电视要在同一个 Wi-Fi 下。</p>");
        html.append("<label class='pick' for='f'>＋ 选择文件</label>");
        html.append("<input type='file' id='f' accept='image/*,video/*' multiple>");
        html.append("<div id='list'></div><script>");
        html.append("const input=document.getElementById('f'),list=document.getElementById('list');");
        html.append("input.addEventListener('change',()=>{");
        html.append("for(const f of input.files){upload(f);}input.value='';});");
        html.append("function upload(file){");
        html.append("const row=document.createElement('div');row.className='item';");
        html.append("row.innerHTML='<div>'+file.name+'</div>");
        html.append("<div class=\\'bar\\'><div class=\\'fill\\'></div></div>';");
        html.append("list.appendChild(row);");
        html.append("const fill=row.querySelector('.fill');");
        html.append("const xhr=new XMLHttpRequest();");
        html.append("xhr.open('POST','/upload?t='+tk+'&name='+encodeURIComponent(file.name));");
        html.append("xhr.upload.onprogress=e=>{if(e.lengthComputable){");
        html.append("fill.style.width=(e.loaded/e.total*100).toFixed(1)+'%';}};");
        html.append("xhr.onload=()=>{if(xhr.status===200){fill.style.width='100%';");
        html.append("row.insertAdjacentHTML('beforeend','<div class=\\'ok\\'>完成</div>');}");
        html.append("else{row.insertAdjacentHTML('beforeend','<div class=\\'err\\'>失败：'");
        html.append("+xhr.responseText+'</div>');}};");
        html.append("xhr.onerror=()=>row.insertAdjacentHTML('beforeend',");
        html.append("'<div class=\\'err\\'>网络中断，检查是否同一 Wi-Fi</div>');");
        html.append("xhr.send(file);}");
        html.append("</script></body></html>");
        return html.toString();
    }
}
