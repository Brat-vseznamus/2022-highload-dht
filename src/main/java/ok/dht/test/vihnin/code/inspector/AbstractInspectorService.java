package ok.dht.test.vihnin.code.inspector;

import one.nio.http.Request;
import one.nio.http.Response;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

public abstract class AbstractInspectorService implements InspectorService {
    protected Supplier<Long> countProvider;
    protected Supplier<Long> taskCountProvider;

    protected Formatter<InspectorData, byte[]> dataFormatter = new Formatter<>() {
        @Override
        public byte[] to(InspectorData input) {
            return new StringBuilder()
                    .append(input.time)
                    .append("|")
                    .append(input.keyCount)
                    .append("|")
                    .append(input.taskCount)
                    .toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public InspectorData from(byte[] output) {
            String s = new String(output, StandardCharsets.UTF_8);
            String[] elements = s.split("\\|");
            return new InspectorData(
                    Long.parseLong(elements[0]),
                    Long.parseLong(elements[1]),
                    Long.parseLong(elements[2])
            );
        }
    };

    @Override
    public void setData(Supplier<Long> countProvider, Supplier<Long> numberOfTaskProvider) {
        this.countProvider = countProvider;
        this.taskCountProvider = numberOfTaskProvider;
    }

    @Override
    public Response handleInfoRequest(Request request) {
        long currTime = System.currentTimeMillis();
        return Response.ok(dataFormatter.to(
                new InspectorData(
                        currTime,
                        countProvider.get(),
                        taskCountProvider.get()
                )
        ));
    }

    protected record InspectorData(long time, long keyCount, long taskCount) {

        @Override
            public String toString() {
                return "InspectorData{" +
                        "time=" + time +
                        ", keyCount=" + keyCount +
                        ", taskCount=" + taskCount +
                        '}';
            }
        }
}
