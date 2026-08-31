package mindless.runtime;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;

public class MemoryURLConnection extends URLConnection {
    private byte[] data;

    MemoryURLConnection(URL url) {
        super(url);
    }

    @Override
    public void connect() throws IOException {
        if (connected) return;
        String path = url.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        data = MemoryResourceStore.get(path);
        if (data == null) throw new FileNotFoundException(path);
        connected = true;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        connect();
        return new ByteArrayInputStream(data);
    }

    @Override
    public int getContentLength() {
        try { connect(); } catch (IOException ignored) {}
        return data != null ? data.length : -1;
    }
}
