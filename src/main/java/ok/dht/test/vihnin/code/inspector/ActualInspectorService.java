package ok.dht.test.vihnin.code.inspector;

import ok.dht.ServiceConfig;
import ok.dht.test.vihnin.code.ServiceUtils;
import ok.dht.test.vihnin.code.database.DataBase;
import ok.dht.test.vihnin.code.database.DataBaseRocksDBImpl;
import one.nio.http.Request;
import one.nio.http.Response;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ok.dht.test.vihnin.code.ServiceUtils.SERVICE_ACCUM_ENDPOINT;

public class ActualInspectorService extends AbstractInspectorService {
    private static final Integer ASK_INTERVAL = 1000;
    private static final Logger logger = LoggerFactory.getLogger(ActualInspectorService.class);
    private final List<String> urls;
    private final TimerTask infoAccumulator;
    private final ThreadLocal<Map<String, HttpClient>> javaClients;
    private final Map<String, DataBase<String, byte[]>> nodeDatabases;

    private final ExecutorService executorService = new ThreadPoolExecutor(
            8,
            8,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100)
    );

    public ActualInspectorService(ServiceConfig serviceConfig) {
        this.urls = serviceConfig.clusterUrls();
        this.javaClients = ThreadLocal.withInitial(() -> {
            Map<String, HttpClient> baseMap = new HashMap<>();
            for (String url : serviceConfig.clusterUrls()) {
                baseMap.put(url, HttpClient.newBuilder().executor(executorService).build());
            }
            return baseMap;
        });
        this.infoAccumulator = new TimerTask() {
            @Override
            public void run() {
                for (String url : urls) {
                    try {
                        var r = ServiceUtils.createJavaRequest(
                                new Request(
                                        Request.METHOD_GET,
                                        SERVICE_ACCUM_ENDPOINT,
                                        false
                                ),
                                url
                        );
                        javaClients.get().get(url)
                                .sendAsync(r, HttpResponse.BodyHandlers.ofByteArray())
                                .handleAsync((httpResponse, throwable) -> {
                                    if (throwable == null) {
                                        var data = dataFormatter.from(httpResponse.body());
                                        if (nodeDatabases.containsKey(url)) {
                                            nodeDatabases.get(url)
                                                    .put(
                                                            Long.toString(data.time()),
                                                            httpResponse.body()
                                                    );
                                        }
                                    } else {
                                        logger.error("ERROR ON " + url, throwable);
                                    }
                                    return httpResponse;
                                }, executorService);
                    } catch (URISyntaxException e) {
                        throw new RuntimeException(e);
                    }

                }
            }
        };

        this.nodeDatabases = new HashMap<>();
        int i = 0;
        for (var url : urls) {
            try {
                this.nodeDatabases.put(
                        url,
                        new DataBaseRocksDBImpl(
                                Path.of(
                                        serviceConfig.workingDir().toString(),
                                        Integer.toString(i)
                                )
                        )
                );
            } catch (RocksDBException e) {
                logger.error("CANT CREATE DB FOR " + url, e);
            }
            i++;
        }
    }

    @Override
    public Response handleRequest(Request request) {
        return switch (request.getMethod()) {
            case Request.METHOD_GET -> handleGet(request);
            default -> null;
        };
    }

    @Override
    public void start() {
        Timer timer = new Timer("Timer");
        timer.scheduleAtFixedRate(infoAccumulator, ASK_INTERVAL, ASK_INTERVAL);
    }

    @Override
    public void stop() {
        infoAccumulator.cancel();
    }

    private Response handleGet(Request request) {
        String timeFrom = request.getParameter("from=");
        String timeTo = request.getParameter("to=");

        if (timeFrom == null || timeTo == null) {
            return new Response(Response.BAD_REQUEST,
                    "Time to and from must be as parameters".getBytes(StandardCharsets.UTF_8));
        }

        long tFrom = -1;
        long tTo = -1;

        try {
            tFrom = Long.parseLong(timeFrom);
            tTo = Long.parseLong(timeTo);
        } catch (NumberFormatException e) {
            return new Response(Response.BAD_REQUEST,
                    "Times must be longs".getBytes(StandardCharsets.UTF_8));
        }

        if (tFrom > tTo) {
            return new Response(Response.BAD_REQUEST,
                    "Time from must be <= time to".getBytes(StandardCharsets.UTF_8));
        }

        String shard = request.getParameter("shard=");

        if (shard == null) {
            return new Response(Response.BAD_REQUEST,
                    "Must be a shard number".getBytes(StandardCharsets.UTF_8));
        }

        int shardId = -1;

        try {
            shardId = Integer.parseInt(shard);
        } catch (NumberFormatException e) {
            return new Response(Response.BAD_REQUEST,
                    "Shard must be a number".getBytes(StandardCharsets.UTF_8));
        }

        if (!(0 <= shardId && shardId < urls.size())) {
            return new Response(Response.BAD_REQUEST,
                    "Illegal shard".getBytes(StandardCharsets.UTF_8));
        }

        String url = urls.get(shardId);
        StringBuilder answer = new StringBuilder("{\n");

        if (nodeDatabases.containsKey(url)) {
            var db = nodeDatabases.get(url);
            answer.append("values: [\n");
            var first = true;
            for (var iterator = db.getRange(timeFrom, timeTo); iterator.hasNext();) {
                if (!first) {
                    answer.append(",\n");
                } else {
                    first = false;
                }
                var curr = iterator.next();
                var data = dataFormatter.from(curr.getValue());
                answer.append("{")
                        .append("time: ")
                        .append(data.time())
                        .append(", ")
                        .append("keys: ")
                        .append(data.keyCount())
                        .append(", ")
                        .append("task_in_queue: ")
                        .append(data.taskCount())
                        .append("}");
            }
            answer.append("]\n");
        }

        answer.append("}");

        return Response.ok(answer.toString());
    }
}
