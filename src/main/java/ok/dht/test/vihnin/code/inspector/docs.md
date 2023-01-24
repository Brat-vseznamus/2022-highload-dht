# Inspector Service

## Описание

В рамках данного задания я хотел реализовать сервис, который бы, параллельно работая
с основными механизмами, мог выдать полезную статистику о нагрузке шардов.

В качестве статистики я выбрал *число ключей* и *число тасок в очереди*, и всё это на
момент замера. Таким образом сторонним запросом можно было узнать последовательность
таких замеров в выбранный промежуток времени с времени *от* и *до* на шарде с выбранным
номером. Но об этом позже.


## Реализация

Сначала был разработан интерфейс, который отражал бы основную суть:

```java
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
```

Где первый метод отвечает за запросы на сам сервис, а второй за запросы с замерами.

Далее был написан абстрактный класс для дальнейших реализаций:

```java 
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
```

В котором был создан 
* Объект данных замеров и их перевод в байтики и восстановление из
них (за это ответствен `dataFromatter`)
* Общая реализация ответа на просьбу замерить данные

Далее были написаны два класса `RedirectInspectorService` и `ActualInspectorService`, первый из которых
только отдает замеры, но на запрос данных редиректит на ноду с размещенном на ней сервисом, а второй класс
уже реализует сам сервис.

### Актуальный сервис

Нужно было при запуске было:
* создать базы данных под каждую из рабочих нод (к сожалению в роксдб
нельзя сделать более умные таблички например с тремя колонками, так что сделал как мог) с сохраненными замерами
* запустить периодических процесс, который бы опрашивал все ноды для замеров
* реализовать выдачу результатов по запросы

Для первой части так же как и раньше создавались инстансы `DataBaseRocksDBImpl` как-то так

```java
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
```

Для второй части надо было сначала выделить ендпоинты по котором бы собиралась бы
и отдавалась информация

```java
public static final String SERVICE_ENDPOINT = "/v0/inspector";
public static final String SERVICE_ACCUM_ENDPOINT = SERVICE_ENDPOINT + "/info";
```

Тогда остается дело за малым: написать запускающийся по таймеру процесс, который бы
делал запрос GET на `SERVICE_ACCUM_ENDPOINT` и выписывал бы ответы в базу, потому получился вот такой код:

```java
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
```

Всё, остается лишь научится выдавать результаты. Для этого я сделал метод, который обрабатывает
GET запросы на `SERVICE_ENDPOINT`, проверяет в них наличия `from` и `to` времени а также `shard`.
Дальше все строки с временем между ту и фром собирались для этого шарда в джейсон и отдавались, вот пример:

```
curl -X GET -v "http://localhost:19234/v0/inspector?from=1674526831576&to=1674526859575&shard=1"
Note: Unnecessary use of -X or --request, GET is already inferred.
*   Trying 127.0.0.1:19234...
* Connected to localhost (127.0.0.1) port 19234 (#0)
> GET /v0/inspector?from=1674526831576&to=1674526859575&shard=1 HTTP/1.1
> Host: localhost:19234
> User-Agent: curl/7.81.0
> Accept: */*
> 
* Mark bundle as not supporting multiuse
< HTTP/1.1 200 OK
< Content-Length: 1533
< Content-Type: text/plain; charset=utf-8
< Connection: Keep-Alive
< 
{
values: [
{time: 1674526831576, keys: 44918, task_in_queue: 0},
{time: 1674526832575, keys: 45917, task_in_queue: 5},
{time: 1674526833576, keys: 48192, task_in_queue: 0},
{time: 1674526834576, keys: 35290, task_in_queue: 23},
{time: 1674526835575, keys: 44968, task_in_queue: 4},
{time: 1674526836575, keys: 45723, task_in_queue: 0},
{time: 1674526837575, keys: 46169, task_in_queue: 0},
{time: 1674526838575, keys: 46615, task_in_queue: 0},
{time: 1674526839576, keys: 34056, task_in_queue: 3},
{time: 1674526840576, keys: 44074, task_in_queue: 8},
{time: 1674526841575, keys: 44487, task_in_queue: 0},
{time: 1674526842575, keys: 44871, task_in_queue: 0},
{time: 1674526843659, keys: 50704, task_in_queue: 16},
{time: 1674526844575, keys: 53139, task_in_queue: 49},
{time: 1674526845576, keys: 60058, task_in_queue: 28},
{time: 1674526846576, keys: 55313, task_in_queue: 9},
{time: 1674526847575, keys: 52909, task_in_queue: 0},
{time: 1674526848575, keys: 55600, task_in_queue: 0},
{time: 1674526849575, keys: 55447, task_in_queue: 25},
{time: 1674526850575, keys: 56855, task_in_queue: 4},
{time: 1674526851576, keys: 68535, task_in_queue: 15},
{time: 1674526852576, keys: 69287, task_in_queue: 18},
{time: 1674526853575, keys: 71693, task_in_queue: 15},
{time: 1674526854575, keys: 73210, task_in_queue: 5},
{time: 1674526855647, keys: 75362, task_in_queue: 8},
{time: 1674526856575, keys: 78504, task_in_queue: 0},
{time: 1674526857576, keys: 80929, task_in_queue: 4},
{time: 1674526858576, keys: 77035, task_in_queue: 0}]
* Connection #0 to host localhost left intact
}⏎                                                                              

```

Код сборки джейсона не представляет из себя что-то интереснее чем перебор по итератору
строчек и их парсинг но все же код такой:

```java
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
```

## Интеграция в основной процесс.

Для этого я переписал `ServiceConfig`, чтобы он принимал порт на котором будет работать инспектор, потому
когда запускался сам сервер выбирались разные версии сервиса:

```java
public ParallelHttpServer(
            ServiceConfig config,
            ResponseManager responseManager,
            Object... routers) throws IOException {
    // Не важно
    this.inspectorService = config.getInspectorPort() == config.selfPort()
            ? new ActualInspectorService(config)
            : new RedirectInspectorService(config.selfUrl() + ":" + config.getInspectorPort());

    // Не важно
    this.inspectorService.setData(
            responseManager.storage::count,
            () -> (long) ((ThreadPoolExecutor) executorService).getQueue().size()
    );

    this.inspectorService.start();

    // Не важно
}
```

В первой части определялось какую реализацию выбрать так, чтобы рабочий сервис был только на одной
ноде.

Далее каждому инспектору объяснялось откуда брать данные с замерами, точнее как их получать, без
передачи ссылок на сами объекты.

И в итоге каждый из них стартовал вместе со всем сервером.

Далее нужно было обработать приходящие запросы на сервер на выделенные эндпоинты, для этого была добавлена
следующая проверка:

```java
if (request.getPath().equals(SERVICE_ENDPOINT)) {
    var response = inspectorService.handleRequest(request);
    if (response == null) {
        session.sendError(Response.SERVICE_UNAVAILABLE, "unknown reason");
    } else {
        session.sendResponse(response);
    }
}
if (request.getPath().equals(SERVICE_ACCUM_ENDPOINT)) {
    var response = inspectorService.handleInfoRequest(request);
    if (response == null) {
        session.sendError(Response.SERVICE_UNAVAILABLE, "unknown reason");
    } else {
        session.sendResponse(response);
    }
}
```
## Проверка работоспособности

Я запускал две ноды и через пут запросы записывал на них данные, чтобы проверить поступают ли обновления
о числе ключей испектору.

Далее создавал нагрузку и смотрел как будет менять число задач в пуле (как раз отражено в примере)
и сранивал с результатами нагрузки: если часто число задач было 0 или небольшим относительно числом,
то все оё - сервис справляется, а значит стоит ожидать выдерживание нагрузки. В случае захлебвания
частота замеров понижалась, а число тасок в очереди вместе с числом ключей сильно росло, 
что соответствовало действительности.
