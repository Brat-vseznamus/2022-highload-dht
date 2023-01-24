package ok.dht.test.vihnin.code.inspector;

import one.nio.http.Request;
import one.nio.http.Response;

import java.util.function.Supplier;

public interface InspectorService {
    Response handleRequest(Request request);
    Response handleInfoRequest(Request request);

    void setData(
            Supplier<Long> countProvider,
            Supplier<Long> numberOfTaskProvider
    );

    void start();
    void stop();
}
