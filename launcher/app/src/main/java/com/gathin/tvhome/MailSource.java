package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * Notes and photos by email, so nobody has to install anything: family mail the TV's own address
 * from the mail app their phone already has, from anywhere. Only the addresses listed in
 * mail_allowed_senders are accepted; everything else in the mailbox is ignored.
 *
 * Read-only IMAP: the TV never deletes or sends mail. Use a mailbox made just for this, with an
 * app password rather than your real one (see windows/README.md).
 */
final class MailSource {
    private static final int LOOK_BACK = 12;                     // newest messages to examine
    private static final long KEEP_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_ATTACHMENT = 8 * 1024 * 1024;

    private MailSource() {}

    static boolean configured(Context c) {
        return !c.getString(R.string.mail_user).trim().isEmpty()
                && !c.getString(R.string.mail_password).trim().isEmpty()
                && !allowed(c).isEmpty();
    }

    private static Set<String> allowed(Context c) {
        Set<String> out = new HashSet<>();
        for (String s : c.getString(R.string.mail_allowed_senders).split("[,;\\s]+")) {
            s = s.trim().toLowerCase();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** Checks the mailbox and adds anything new to the board. Call from a background thread. */
    static void poll(Context c) throws Exception {
        if (!configured(c)) return;
        String host = c.getString(R.string.mail_host).trim();
        if (host.isEmpty()) host = "imap.gmail.com";
        String user = c.getString(R.string.mail_user).trim();
        String pass = c.getString(R.string.mail_password).trim();
        Set<String> senders = allowed(c);
        int[] screen = Photos.screen(c);

        Properties props = new Properties();
        props.setProperty("mail.store.protocol", "imaps");
        props.setProperty("mail.imaps.ssl.enable", "true");
        props.setProperty("mail.imaps.connectiontimeout", "12000");
        props.setProperty("mail.imaps.timeout", "20000");
        props.setProperty("mail.imaps.writetimeout", "20000");

        Store store = Session.getInstance(props).getStore("imaps");
        try {
            store.connect(host, 993, user, pass);
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                int count = inbox.getMessageCount();
                if (count <= 0) return;
                Message[] msgs = inbox.getMessages(Math.max(1, count - LOOK_BACK + 1), count);
                long cutoff = System.currentTimeMillis() - KEEP_MS;
                for (int i = msgs.length - 1; i >= 0; i--) {
                    try {
                        consume(msgs[i], senders, cutoff, screen);
                    } catch (Exception ignored) {
                        // one unreadable message must not stop the rest
                    }
                }
            } finally {
                inbox.close(false);
            }
        } finally {
            try { store.close(); } catch (Exception ignored) { }
        }
    }

    private static void consume(Message msg, Set<String> senders, long cutoff, int[] screen)
            throws Exception {
        java.util.Date sent = msg.getSentDate() != null ? msg.getSentDate() : msg.getReceivedDate();
        if (sent == null || sent.getTime() < cutoff) return;

        String from = "", who = "";
        javax.mail.Address[] addrs = msg.getFrom();
        if (addrs != null && addrs.length > 0 && addrs[0] instanceof InternetAddress) {
            InternetAddress ia = (InternetAddress) addrs[0];
            from = ia.getAddress() == null ? "" : ia.getAddress().toLowerCase();
            who = ia.getPersonal() != null ? ia.getPersonal() : "";
            if (who.isEmpty() && from.contains("@")) who = from.substring(0, from.indexOf('@'));
        }
        if (!senders.contains(from)) return;

        String baseId = msg instanceof MimeMessage ? ((MimeMessage) msg).getMessageID() : null;
        if (baseId == null || baseId.isEmpty()) baseId = from + ":" + sent.getTime();
        String noteId = "mail:" + baseId;
        String photoId = "mail:p:" + baseId;
        if (Board.has(noteId) || Board.has(photoId)) return;

        String subject = msg.getSubject() == null ? "" : msg.getSubject().trim();
        String text = "";
        Bitmap photo = null;

        Object content = msg.getContent();
        if (content instanceof String) {
            text = ((String) content).trim();
        } else if (content instanceof Multipart) {
            Multipart mp = (Multipart) content;
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String type = part.getContentType() == null ? "" : part.getContentType().toLowerCase();
                boolean attached = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())
                        || (part.getFileName() != null && !part.getFileName().isEmpty());
                if (photo == null && type.startsWith("image/")) {
                    photo = Photos.decode(readPart(part), screen[0], screen[1]);
                } else if (text.isEmpty() && type.startsWith("text/plain") && !attached) {
                    Object body = part.getContent();
                    if (body instanceof String) text = ((String) body).trim();
                }
            }
        }

        // the subject is usually the real message; the body is often a signature
        String line = !subject.isEmpty() ? subject : firstLine(text);
        if (line.length() > 160) line = line.substring(0, 157) + "...";
        if (photo == null && line.isEmpty()) return;

        Board.add(new Board.Item(photo != null ? photoId : noteId, who, line, photo,
                System.currentTimeMillis()));
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\r?\n")) {
            line = line.trim();
            if (!line.isEmpty()) return line;
        }
        return "";
    }

    private static byte[] readPart(Part part) throws Exception {
        try (InputStream in = part.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < MAX_ATTACHMENT) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }
}
