package ok.dht.test.vihnin;

import one.nio.http.HttpSession;
import one.nio.http.Request;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static ok.dht.test.vihnin.ParallelHttpServer.processAcknowledgment;

public class ResponseAccumulator {
    private final HttpSession session;
    private final int method;
    private final int ack;
    private final int from;

    private final AtomicInteger acknowledged;
    private final AtomicInteger answered;
    private final AtomicReference<Data> bestData;

    private final AtomicBoolean send;

    public ResponseAccumulator(HttpSession session, int method, int ack, int from) {
        this.session = session;
        this.method = method;
        this.ack = ack;
        this.from = from;
        this.acknowledged = new AtomicInteger(0);
        this.answered = new AtomicInteger(0);
        this.bestData = new AtomicReference<>(new Data(-1, -1, null));
        this.send = new AtomicBoolean(false);
    }

    public void acknowledgeMissed() {
        answer(acknowledged.get());
    }

    public void acknowledgeFailed() {
        answer(acknowledged.get());
    }

    public void acknowledgeSucceed(Long time, Integer status, byte[] data) {
        if (method == Request.METHOD_GET) {
            Data newData = new Data(status, time, data);
            Data curData = bestData.get();
            while (curData.time < time) {
                if (bestData.compareAndSet(curData, newData)) {
                    break;
                }
                curData = bestData.get();
            }
        }

        int curAck = acknowledged.incrementAndGet();

        answer(curAck);
    }

    private void answer(int curAck) {
        int curAnswered = answered.incrementAndGet();

        if ((curAck >= ack || curAnswered == from)
                && send.compareAndSet(false, true)) {

            Data curData = this.bestData.get();
            processAcknowledgment(
                    this.method,
                    this.session,
                    acknowledged.get() >= this.ack,
                    curData.status,
                    curData.bytes.get()
            );
        }
    }

    private static class Data {
        private final int status;
        private final long time;
        private final AtomicReference<byte[]> bytes;

        public Data(int status, long time, byte[] bytes) {
            this.status = status;
            this.time = time;
            this.bytes = new AtomicReference<>(bytes);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (getClass() != o.getClass()) return false;
            Data data = (Data) o;
            return status == data.status && time == data.time;
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, time);
        }
    }
}
