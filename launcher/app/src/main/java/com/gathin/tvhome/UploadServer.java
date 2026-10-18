package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny web page served by the TV itself, so anyone at home can put a note or photo on the screen
 * with nothing installed: open http://<tv ip>:<port> in a phone browser, type, pick a photo, send.
 *
 * Home Wi-Fi only, by design: it listens on the local network and nothing is published to the
 * internet. Anyone on your Wi-Fi can post, so set upload_pin in config.xml if you want a code.
 *
 * Deliberately small: one connection at a time, 8 MB cap per request, no frameworks.
 */
final class UploadServer implements Runnable {
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int BACKLOG = 4;

    private final Context context;
    private final int port;
    private final String pin;
    private final int maxW, maxH;
    private volatile boolean running = true;
    private ServerSocket server;

    UploadServer(Context context) {
        this.context = context.getApplicationContext();
        this.port = Math.max(1024, this.context.getResources().getInteger(R.integer.upload_port));
        this.pin = this.context.getString(R.string.upload_pin).trim();
        int[] s = Photos.screen(this.context);
        this.maxW = s[0];
        this.maxH = s[1];
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing is best effort
        }
    }

    @Override
    public void run() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), BACKLOG);
        } catch (IOException e) {
            return;   // port busy: the rest of the board still works
        }
        while (running) {
            Socket sock = null;
            try {
                sock = server.accept();
                sock.setSoTimeout(20_000);
                handle(sock);
            } catch (Exception ignored) {
                // one bad request must not stop the server
            } finally {
                if (sock != null) try { sock.close(); } catch (IOException ignored) { }
            }
        }
    }

    // ---- request handling ----

    private void handle(Socket sock) throws IOException {
        // Only machines on your own network, never anything routed in from outside.
        if (!isLocal(sock.getInetAddress())) return;

        InputStream in = sock.getInputStream();
        OutputStream out = sock.getOutputStream();

        byte[] head = readUntilBlankLine(in);
        if (head == null) return;
        String headers = new String(head, StandardCharsets.ISO_8859_1);
        String firstLine = headers.substring(0, Math.max(0, headers.indexOf('\n'))).trim();
        boolean post = firstLine.startsWith("POST");

        // A form on some other website must not be able to push images onto the TV while a phone
        // on this Wi-Fi happens to be browsing it. Browsers send Origin on cross-site posts.
        if (post) {
            String origin = textHeader(headers, "origin");
            if (origin != null && !origin.isEmpty()) {
                String o = origin.replaceFirst("(?i)^https?://", "").trim();
                String host = textHeader(headers, "host");
                if (host == null || !o.equalsIgnoreCase(host.trim())) {
                    send(out, 403, "text/plain; charset=utf-8",
                            context.getString(R.string.upload_blocked).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
        }

        if (!post) {
            send(out, 200, "text/html; charset=utf-8", page(null).getBytes(StandardCharsets.UTF_8));
            return;
        }

        int len = intHeader(headers, "content-length");
        if (len <= 0 || len > MAX_BODY) {
            send(out, 413, "text/plain", "too big".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = readExactly(in, len);
        String boundary = boundary(headers);
        String message;
        if (boundary == null) {
            message = context.getString(R.string.upload_failed);
        } else {
            message = accept(body, boundary);
        }
        send(out, 200, "text/html; charset=utf-8", page(message).getBytes(StandardCharsets.UTF_8));
    }

    /** Pulls the name, note and photo out of a multipart body and puts them on the board. */
    private String accept(byte[] body, String boundary) {
        byte[] sep = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        String who = "", note = "", givenPin = "", clear = "";
        Bitmap photo = null;
        int at = indexOf(body, sep, 0);
        while (at >= 0) {
            int partStart = at + sep.length;
            int next = indexOf(body, sep, partStart);
            if (next < 0) break;
            int headEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart);
            if (headEnd < 0 || headEnd > next) break;
            String partHead = new String(body, partStart, headEnd - partStart, StandardCharsets.ISO_8859_1);
            int from = headEnd + 4;
            int to = next - 2;                       // trailing CRLF before the next boundary
            if (to < from) to = from;
            String name = quoted(partHead, "name=\"");
            if ("photo".equals(name)) {
                if (to > from) photo = Photos.decode(slice(body, from, to), maxW, maxH);
            } else if (name != null) {
                String value = new String(body, from, to - from, StandardCharsets.UTF_8).trim();
                if ("who".equals(name)) who = value;
                else if ("note".equals(name)) note = value;
                else if ("pin".equals(name)) givenPin = value;
                else if ("clear".equals(name)) clear = value;
            }
            at = next;
        }

        if (!pin.isEmpty() && !pin.equals(givenPin)) return context.getString(R.string.upload_bad_pin);
        if (!clear.isEmpty()) {
            Board.clear();
            return context.getString(R.string.upload_cleared);
        }
        if (note.length() > 160) note = note.substring(0, 157) + "...";
        if (photo == null && note.isEmpty()) return context.getString(R.string.upload_empty);

        String id = "up:" + System.currentTimeMillis() + ":" + (photo != null ? "p" : "n");
        Board.add(new Board.Item(id, who, note, photo, System.currentTimeMillis()));
        BoardService.announceNewest(context);
        return context.getString(photo != null ? R.string.upload_sent_photo : R.string.upload_sent_note);
    }

    // ---- the page ----

    private String page(String message) {
        String banner = message == null ? ""
                : "<p class=ok>" + escape(message) + "</p>";
        return "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>" + escape(context.getString(R.string.upload_title)) + "</title><style>"
                + ":root{color-scheme:dark}"
                + "*{box-sizing:border-box}"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "padding:24px;background:#0b0b0d;color:#f4f4f5;"
                + "font:16px/1.5 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
                + "form{width:100%;max-width:420px}"
                + "h1{font-size:22px;font-weight:600;margin:0 0 4px}"
                + "p.sub{margin:0 0 24px;color:#a1a1aa;font-size:14px}"
                + "label{display:block;margin:16px 0 6px;font-size:13px;color:#a1a1aa}"
                + "input,textarea{width:100%;padding:12px 14px;border-radius:12px;font-size:16px;"
                + "background:#18181b;color:#f4f4f5;border:1px solid #2a2a30}"
                + "textarea{min-height:92px;resize:vertical}"
                + "input[type=file]{padding:10px;border-style:dashed}"
                + "button{margin-top:22px;width:100%;padding:15px;border:0;border-radius:12px;"
                + "background:#f4f4f5;color:#0b0b0d;font-size:16px;font-weight:600}"
                + "button.ghost{margin-top:10px;background:transparent;color:#a1a1aa;"
                + "border:1px solid #2a2a30;font-weight:500}"
                + "p.ok{margin:0 0 18px;padding:12px 14px;border-radius:12px;"
                + "background:#132a1c;border:1px solid #1f5133;color:#86efac;font-size:14px}"
                + "</style></head><body><form method=post enctype=multipart/form-data>"
                + "<h1>" + escape(context.getString(R.string.upload_title)) + "</h1>"
                + "<p class=sub>" + escape(context.getString(R.string.upload_sub)) + "</p>"
                + banner
                + "<label for=who>Your name</label><input id=who name=who maxlength=24>"
                + "<label for=note>Note</label><textarea id=note name=note maxlength=160></textarea>"
                + "<label for=photo>Photo</label>"
                + "<input id=photo type=file name=photo accept=image/*>"
                + (pin.isEmpty() ? "" : "<label for=pin>Code</label><input id=pin name=pin>")
                + "<button type=submit>" + escape(context.getString(R.string.upload_send)) + "</button>"
                + "<button type=submit name=clear value=1 class=ghost>"
                + escape(context.getString(R.string.upload_clear)) + "</button>"
                + "<script>var k='tvname';var w=document.getElementById('who');"
                + "try{w.value=localStorage.getItem(k)||''}catch(e){}"
                + "w.form.addEventListener('submit',function(){try{localStorage.setItem(k,w.value)}catch(e){}});"
                + "</script></form></body></html>";
    }

    // ---- small helpers ----

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static byte[] readUntilBlankLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int match = 0;
        while (buf.size() < 16 * 1024) {
            int b = in.read();
            if (b < 0) return buf.size() == 0 ? null : buf.toByteArray();
            buf.write(b);
            if (b == '\r' || b == '\n') {
                match += (b == '\n') ? 1 : 0;
                if (match == 2) return buf.toByteArray();
            } else {
                match = 0;
            }
        }
        return buf.toByteArray();
    }

    private static byte[] readExactly(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int read = 0;
        while (read < len) {
            int n = in.read(data, read, len - read);
            if (n < 0) break;
            read += n;
        }
        return read == len ? data : slice(data, 0, read);
    }

    /** True for loopback and ordinary home-network addresses (10.x, 172.16-31.x, 192.168.x). */
    private static boolean isLocal(java.net.InetAddress a) {
        return a != null
                && (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress());
    }

    private static String textHeader(String headers, String name) {
        for (String line : headers.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static int intHeader(String headers, String name) {
        for (String line : headers.split("\r?\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                try {
                    return Integer.parseInt(line.substring(c + 1).trim());
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String boundary(String headers) {
        int i = headers.toLowerCase().indexOf("boundary=");
        if (i < 0) return null;
        String rest = headers.substring(i + 9).trim();
        int end = rest.indexOf('\r');
        if (end < 0) end = rest.length();
        String b = rest.substring(0, end).trim();
        if (b.startsWith("\"")) {
            int q = b.indexOf('"', 1);
            b = q > 0 ? b.substring(1, q) : b.substring(1);
        }
        int semi = b.indexOf(';');
        if (semi > 0) b = b.substring(0, semi).trim();
        return b.isEmpty() ? null : b;
    }

    private static String quoted(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = s.indexOf('"', start);
        return end < 0 ? null : s.substring(start, end);
    }

    private static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[Math.max(0, to - from)];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
