package ru.winkiz.lab4;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Plain CSV, Markdown tables and PNG graphs, without third-party dependencies. */
final class ReportWriter {
    record Observation(String phase, int run, ScenarioRunner.Result result) { }
    private record Summary(String name, ScenarioRunner.Result example, int runs, boolean passed,
                           double seconds, double min, double max, double throughput,
                           double byteRate, double deviation, double latency, double p95) { }
    private ReportWriter() { }

    static void write(Path output, List<Observation> rows, boolean local) throws IOException {
        StringBuilder csv = new StringBuilder("experiment,phase,run,scenario,senders,receivers,payload_bytes,tcp_nodelay,sent,received,unique,lost,duplicates,unexpected,corrupted,time_seconds,throughput,bytes_per_second,deviation_pp,latency_median_ms,latency_p95_ms,result,error\n");
        StringBuilder shares = new StringBuilder("experiment,phase,run,receiver,count,share_percent\n");
        Map<String, List<ScenarioRunner.Result>> groups = new LinkedHashMap<>();
        for (Observation observation : rows) {
            var r = observation.result();
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%.9f,%.6f,%.6f,%.9f,%.6f,%.6f,%s,%s%n",
                    r.name(), observation.phase(), observation.run(), r.scenario(), r.senders(), r.receivers(), r.payloadBytes(), r.tcpNoDelay(), r.sent(), r.received(), r.unique(), r.lost(), r.duplicates(), r.unexpected(), r.corrupted(), r.seconds(), r.throughput(), r.bytesPerSecond(), r.deviation(), r.medianLatencyMs(), r.p95LatencyMs(), r.passed() ? "PASS" : "FAIL", quote(r.error())));
            for (int i = 0; i < r.counts().length; i++) shares.append(String.format(Locale.ROOT, "%s,%s,%d,R%d,%d,%.9f%n", r.name(), observation.phase(), observation.run(), i + 1, r.counts()[i], r.received() == 0 ? 0 : 100.0 * r.counts()[i] / r.received()));
            if (observation.phase().equals("measurement")) groups.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
        }
        Files.writeString(output.resolve("measurements.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("shares.csv"), shares, StandardCharsets.UTF_8);
        List<Summary> summary = new ArrayList<>();
        StringBuilder table = new StringBuilder("experiment,runs,result,median_seconds,min_seconds,max_seconds,median_msg_s,median_bytes_s,median_D_pp,median_latency_ms,median_p95_latency_ms\n");
        for (var entry : groups.entrySet()) {
            var values = entry.getValue();
            var s = new Summary(entry.getKey(), values.getFirst(), values.size(), values.stream().allMatch(ScenarioRunner.Result::passed),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::seconds).toArray()),
                    values.stream().mapToDouble(ScenarioRunner.Result::seconds).min().orElseThrow(),
                    values.stream().mapToDouble(ScenarioRunner.Result::seconds).max().orElseThrow(),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::throughput).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::bytesPerSecond).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::deviation).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::medianLatencyMs).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::p95LatencyMs).toArray()));
            summary.add(s);
            table.append(String.format(Locale.ROOT, "%s,%d,%s,%.9f,%.9f,%.9f,%.6f,%.6f,%.9f,%.6f,%.6f%n", s.name(), s.runs(), s.passed() ? "PASS" : "FAIL", s.seconds(), s.min(), s.max(), s.throughput(), s.byteRate(), s.deviation(), s.latency(), s.p95()));
        }
        Files.writeString(output.resolve("summary.csv"), table, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("# Лабораторная №4. Результаты эксперимента\n\n");
        report.append(local ? "Сервер: вспомогательный LocalBroker на loopback. Это реальные TCP-измерения локального стенда, а не результаты сервера преподавателя или VPS.\n\n" : "Сервер: внешний TCP-брокер; адрес указан в environment.txt.\n\n");
        report.append("Сырые запуски, включая прогревы: measurements.csv. Доли каждого получателя в каждом запуске: shares.csv. В итогах ниже учитываются только основные измерения. Среда, seed, таймаут и число повторов: environment.txt.\n\n")
                .append("Время измеряется System.nanoTime() непосредственно перед первой отправкой и до последней ожидаемой доставки. Генерация, подготовка соединений (кроме позднего подключения в сценарии 1), завершающее наблюдение за дубликатами, закрытие и экспорт вне измерения. В сценарии 2 в измерение входят барьеры приёма и отключение R1. При FAIL указывается время до остановки/дедлайна; его нельзя трактовать как время полной доставки.\n\n")
                .append("X=N/T, где N — фактическое количество доставленных сообщений. B=N×размер_UTF8/T — полезная нагрузка в байтах/с, без заголовков TCP и протокола. D=max|доля_i−100/M|, в процентных пунктах.\n\n")
                .append("Для сценария 2 доли 25%/75% заданы условием, поэтому D=25 п.п. ожидаемо и не означает проблему распределения. Для опытов с разным числом получателей и TCP_NODELAY содержимое сообщений при одинаковом размере и seed одинаковое.\n\n")
                .append("## Таблица измерений\n\n| Эксперимент | Повторов | Медиана, с | Мин., с | Макс., с | msg/s | байт/с | D, п.п. | Результат |\n|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (Summary s : summary) report.append(String.format(Locale.ROOT, "| %s | %d | %.6f | %.6f | %.6f | %.1f | %.1f | %.3f | %s |%n", s.name(), s.runs(), s.seconds(), s.min(), s.max(), s.throughput(), s.byteRate(), s.deviation(), s.passed() ? "PASS" : "FAIL"));
        report.append("\n## Сценарий 3: распределение по получателям\n\nКаждая строка — отдельное основное измерение.\n\n| Прогон | Получатель | Количество | Доля, % |\n|---:|---|---:|---:|\n");
        for (Observation o : rows) if (o.phase().equals("measurement") && o.result().name().equals("scenario-3")) {
            var r = o.result();
            for (int i = 0; i < r.counts().length; i++) report.append(String.format(Locale.ROOT, "| %d | R%d | %d | %.2f |%n", o.run(), i + 1, r.counts()[i], r.received() == 0 ? 0 : 100.0 * r.counts()[i] / r.received()));
        }
        report.append("\n## Графики\n\n");
        List<Summary> required = summary.stream().filter(s -> s.name().startsWith("scenario-")).toList();
        draw(output, "scenario-time.png", "Required scenarios: delivery time", "Scenario", "seconds", required, s -> s.seconds(), s -> Integer.toString(s.example().scenario()));
        report.append("![Время сценариев](scenario-time.png)\n\n");
        List<Summary> receiverStudy = summary.stream().filter(s -> s.name().startsWith("receivers-")).toList();
        if (!receiverStudy.isEmpty()) {
            draw(output, "receivers-throughput.png", "Receiver count and throughput", "Receivers (categories)", "msg/s", receiverStudy, Summary::throughput, s -> Integer.toString(s.example().receivers()));
            draw(output, "receivers-deviation.png", "Distribution deviation", "Receivers (categories)", "percentage points", receiverStudy, Summary::deviation, s -> Integer.toString(s.example().receivers()));
            report.append("![Получатели и пропускная способность](receivers-throughput.png)\n\n![Отклонение долей](receivers-deviation.png)\n\n");
        }
        List<Summary> sizes = summary.stream().filter(s -> s.name().startsWith("bytes-")).toList();
        if (!sizes.isEmpty()) {
            draw(output, "size-messages.png", "Payload size and message throughput", "Payload bytes (categories)", "msg/s", sizes, Summary::throughput, s -> Integer.toString(s.example().payloadBytes()));
            draw(output, "size-bytes.png", "Payload size and byte throughput", "Payload bytes (categories)", "MiB/s", sizes, s -> s.byteRate() / (1024 * 1024), s -> Integer.toString(s.example().payloadBytes()));
            report.append("![Размер и msg/s](size-messages.png)\n\n![Размер и МиБ/с](size-bytes.png)\n\n");
        }
        List<Summary> nagle = summary.stream().filter(s -> s.name().startsWith("nodelay-")).toList();
        if (!nagle.isEmpty()) {
            draw(output, "nodelay.png", "TCP_NODELAY (client side)", "TCP_NODELAY", "seconds", nagle, Summary::seconds, s -> Boolean.toString(s.example().tcpNoDelay()));
            report.append("![TCP_NODELAY](nodelay.png)\n\n");
        }
        report.append("## Выводы и ограничения\n\n");
        long failures = rows.stream().filter(o -> !o.result().passed()).count();
        report.append("Всего запусков: ").append(rows.size()).append(". Неуспешных: ").append(failures).append(". Полнота проверяется по множествам ожидаемых и принятых ID, отдельно считаются дубликаты, неизвестные ID и повреждённые тексты. Одного равенства счётчиков недостаточно.\n\n");
        if (!receiverStudy.isEmpty()) {
            Summary best = receiverStudy.stream().filter(Summary::passed).max(Comparator.comparingDouble(Summary::throughput)).orElse(null);
            if (best != null) report.append(String.format(Locale.ROOT, "В этой серии наибольшая медианная скорость среди вариантов числа получателей: %d получателей, %.1f msg/s. Это наблюдение локального стенда, не универсальный оптимум.\n\n", best.example().receivers(), best.throughput()));
        }
        report.append("В сценарии 2 до закрытия R1 разрешено только одно ещё не учтённое сообщение: отправитель ждёт обработки предыдущего через монитор, после 250-го сообщения R1 закрывается и открывает CountDownLatch. Затем отправитель продолжает. Это сохраняет порядок задания и предотвращает уход дополнительных сообщений в фоновый буфер R1. Это синхронизация теста внутри одного процесса, а не ACK сетевого протокола. Сценарий 2 нельзя напрямую сравнивать со свободной отправкой по msg/s.\n\n")
                .append("Без ACK успешная запись в TCP не доказывает обработку сообщения приложением. При произвольном обрыве после успешной записи возможна потеря, а при повторной отправке — дубликат. Поэтому гарантия exactly-once при аварии и безусловное переподключение не заявляются. Условие сценария 2 для неконтролируемой быстрой отправки требует уточнения у преподавателя.\n\n")
                .append("LocalBroker использует round-robin. Близкие доли на нём отражают его алгоритм, а не доказательство равномерности любого брокера. Тест с внешним сервером использует тот же клиент и не меняет ожидаемые значения.\n\n")
                .append("Увеличение числа получателей не обязано ускорять брокер: есть общий диспетчер, блокировки, TCP, переключения потоков. При малых сообщениях значимы системные вызовы, заголовки и flush. Крупные сообщения амортизируют эти расходы, но требуют больше времени на одну доставку. Фактическая зависимость видна на графиках; монотонный рост не предполагается.\n\n")
                .append("В опыте TCP_NODELAY меняется только опция клиентских сокетов. Вспомогательный сервер всегда использует TCP_NODELAY=true. Flush выталкивает Java-буфер, но не отключает алгоритм Нейгла. Loopback не воспроизводит задержку и потери сети VPS.\n\n")
                .append("Задержка доставки измерена между отметкой перед send и извлечением сообщения потоком сценария. Она включает очередь и планирование клиента, не является односторонней сетевой задержкой. p95 считается методом nearest-rank; итоговая таблица содержит медиану p95 отдельных запусков, а не p95 объединённой выборки.\n\n")
                .append("Ответы на все 12 вопросов: ../../docs/ВОПРОСЫ.md. Проверка пунктов задания: ../../docs/ЧЕКЛИСТ.md.\n");
        Files.writeString(output.resolve("ОТЧЁТ.md"), report, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("ОТЧЁТ.txt"), report.toString().replaceAll("!\\[[^]]*]\\([^)]*\\)", "(график см. в PNG-файле рядом)"), StandardCharsets.UTF_8);
    }

    private static String quote(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }
    private static double median(double[] values) {
        java.util.Arrays.sort(values);
        int n = values.length;
        return n % 2 == 0 ? (values[n / 2 - 1] + values[n / 2]) / 2 : values[n / 2];
    }

    private static void draw(Path output, String filename, String title, String xLabel, String yLabel,
                             List<Summary> data, java.util.function.ToDoubleFunction<Summary> value,
                             java.util.function.Function<Summary, String> label) throws IOException {
        BufferedImage image = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE); g.fillRect(0, 0, 900, 500);
        g.setColor(Color.BLACK); g.setFont(new Font("SansSerif", Font.PLAIN, 18)); g.drawString(title, 90, 30);
        int left = 120, right = 850, top = 65, bottom = 410;
        double max = data.stream().mapToDouble(value).max().orElse(1) * 1.1;
        if (max == 0) max = 1;
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        for (int i = 0; i <= 5; i++) {
            int y = bottom - (bottom - top) * i / 5;
            g.setColor(new Color(220, 220, 220)); g.drawLine(left, y, right, y);
            g.setColor(Color.BLACK); g.drawString(String.format(Locale.ROOT, "%.3g", max * i / 5), 38, y + 5);
        }
        g.drawLine(left, top, left, bottom); g.drawLine(left, bottom, right, bottom);
        g.drawString(yLabel, 20, 52); g.drawString(xLabel, 330, 475);
        g.setStroke(new BasicStroke(2));
        int lastX = 0, lastY = 0;
        for (int i = 0; i < data.size(); i++) {
            Summary s = data.get(i);
            int x = data.size() == 1 ? (left + right) / 2 : left + 30 + (right - left - 60) * i / (data.size() - 1);
            int y = bottom - (int) ((bottom - top) * value.applyAsDouble(s) / max);
            g.setColor(s.passed() ? new Color(50, 90, 160) : Color.RED);
            if (i > 0) g.drawLine(lastX, lastY, x, y);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.setColor(Color.BLACK);
            String text = label.apply(s); g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, bottom + 24);
            String number = String.format(Locale.ROOT, "%.4g", value.applyAsDouble(s));
            g.drawString(number, x - g.getFontMetrics().stringWidth(number) / 2, y - 10);
            lastX = x; lastY = y;
        }
        g.dispose(); ImageIO.write(image, "png", output.resolve(filename).toFile());
    }
}
