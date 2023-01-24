package ok.dht.test.vihnin.code.inspector;

import one.nio.http.Request;
import one.nio.http.Response;

public class RedirectInspectorService extends AbstractInspectorService {
    String actualHost;

    public RedirectInspectorService(String actualHost) {
        this.actualHost = actualHost;
    }

    @Override
    public Response handleRequest(Request request) {
        return Response.redirect(actualHost);
    }

    @Override
    public void start() {

    }

    @Override
    public void stop() {

    }
}
