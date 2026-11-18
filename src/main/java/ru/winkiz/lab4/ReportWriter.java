package ru.winkiz.lab4;

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
                           double byteRate, double deviation) { }
    private ReportWriter() { }

    static void write(Path output, List<Observation> rows, boolean local) throws IOException {
        StringBuilder csv = new StringBuilder("experiment,phase,run,scenario,senders,receivers,payload_bytes,tcp_nodelay,sent,received,unique,lost,duplicates,unexpected,corrupted,time_seconds,throughput,bytes_per_second,deviation_pp,result,error\n");
        StringBuilder shares = new StringBuilder("experiment,phase,run,receiver,count,share_percent\n");
        Map<String, List<ScenarioRunner.Result>> groups = new LinkedHashMap<>();
        for (Observation observation : rows) {
            var r = observation.result();
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%.9f,%.6f,%.6f,%.9f,%s,%s%n",
                    r.name(), observation.phase(), observation.run(), r.scenario(), r.senders(), r.receivers(), r.payloadBytes(), r.tcpNoDelay(), r.sent(), r.received(), r.unique(), r.lost(), r.duplicates(), r.unexpected(), r.corrupted(), r.seconds(), r.throughput(), r.bytesPerSecond(), r.deviation(), r.passed() ? "PASS" : "FAIL", quote(r.error())));
            for (int i = 0; i < r.counts().length; i++) shares.append(String.format(Locale.ROOT, "%s,%s,%d,R%d,%d,%.9f%n", r.name(), observation.phase(), observation.run(), i + 1, r.counts()[i], r.received() == 0 ? 0 : 100.0 * r.counts()[i] / r.received()));
            if (observation.phase().equals("measurement")) groups.computeIfAbsent(r.name(), k -> new ArrayList<>()).add(r);
        }
        Files.writeString(output.resolve("measurements.csv"), csv, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("shares.csv"), shares, StandardCharsets.UTF_8);
        List<Summary> summary = new ArrayList<>();
        StringBuilder table = new StringBuilder("experiment,runs,result,median_seconds,min_seconds,max_seconds,median_msg_s,median_bytes_s,median_D_pp\n");
        for (var entry : groups.entrySet()) {
            var values = entry.getValue();
            var s = new Summary(entry.getKey(), values.getFirst(), values.size(), values.stream().allMatch(ScenarioRunner.Result::passed),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::seconds).toArray()),
                    values.stream().mapToDouble(ScenarioRunner.Result::seconds).min().orElseThrow(),
                    values.stream().mapToDouble(ScenarioRunner.Result::seconds).max().orElseThrow(),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::throughput).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::bytesPerSecond).toArray()),
                    median(values.stream().mapToDouble(ScenarioRunner.Result::deviation).toArray()));
            summary.add(s);
            table.append(String.format(Locale.ROOT, "%s,%d,%s,%.9f,%.9f,%.9f,%.6f,%.6f,%.9f%n", s.name(), s.runs(), s.passed() ? "PASS" : "FAIL", s.seconds(), s.min(), s.max(), s.throughput(), s.byteRate(), s.deviation()));
        }
        Files.writeString(output.resolve("summary.csv"), table, StandardCharsets.UTF_8);
        StringBuilder report = new StringBuilder("# Лабораторная №4. Результаты эксперимента\n\n");
        report.append(local ? "Сервер: вспомогательный LocalBroker на loopback. Это реальные TCP-измерения локального стенда, а не результаты сервера преподавателя или VPS.\n\n" : "Сервер: внешний TCP-брокер; адрес указан в environment.txt.\n\n");
        report.append("Сырые запуски, включая прогревы: measurements.csv. Доли каждого получателя в каждом запуске: shares.csv. В итогах ниже учитываются только основные измерения. Среда, seed, таймаут и число повторов: environment.txt.\n\n")
                .append("Время измеряется System.nanoTime() непосредственно перед первой отправкой и до последней ожидаемой доставки. Генерация, подготовка соединений (кроме позднего подключения в сценарии 1), завершающее наблюдение за дубликатами, закрытие и экспорт вне измерения. В сценарии 2 в измерение входят барьеры приёма и отключение R1. При FAIL указывается время до остановки/дедлайна; его нельзя трактовать как время полной доставки.\n\n")
                .append("X=N/T, где N — фактическое количество доставленных сообщений. B=N×размер_UTF8/T — полезная нагрузка в байтах/с, без заголовков TCP и протокола. D=max|доля_i−100/M|, в процентных пунктах.\n\n")
                .append("Для сценария 2 доли 25%/75% заданы условием, поэтому D=25 п.п. ожидаемо и не означает проблему распределения. Для опытов с разным числом получателей и TCP_NODELAY содержимое сообщений при одинаковом размере и seed одинаковое.\n\n")
                .append("## Обязательные сценарии\n\nПо пять измерений после прогрева. Количества указаны для одного измерения; диапазон показывает минимум–максимум пяти значений.\n\n| Сценарий | Отправлено | Получено | Потеряно | Дубликаты | Медиана, с | Сообщений/с | Результат |\n|---|---:|---:|---:|---:|---:|---:|---|\n");
        for (Summary s : summary) if (s.name().startsWith("scenario-")) {
            var values = groups.get(s.name());
            report.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %.6f | %.1f | %s |%n", displayName(s.example()), countRange(values, ScenarioRunner.Result::sent), countRange(values, ScenarioRunner.Result::received), countRange(values, ScenarioRunner.Result::lost), countRange(values, ScenarioRunner.Result::duplicates), s.seconds(), s.throughput(), s.passed() ? "Успех" : "Ошибка"));
        }
        report.append("\n## Сводная таблица экспериментов\n\nМедианы считаются отдельно для каждого показателя.\n\n| Эксперимент | Повторов | Медиана, с | Мин., с | Макс., с | Сообщений/с | Байт/с | D, п.п. | Результат |\n|---|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (Summary s : summary) report.append(String.format(Locale.ROOT, "| %s | %d | %.6f | %.6f | %.6f | %.1f | %.1f | %.3f | %s |%n", displayName(s.example()), s.runs(), s.seconds(), s.min(), s.max(), s.throughput(), s.byteRate(), s.deviation(), s.passed() ? "Успех" : "Ошибка"));
        report.append("\n## Сценарий 3: распределение по получателям\n\nКаждая строка — отдельное основное измерение.\n\n| Прогон | Получатель | Количество | Доля, % |\n|---:|---|---:|---:|\n");
        for (Observation o : rows) if (o.phase().equals("measurement") && o.result().name().equals("scenario-3")) {
            var r = o.result();
            for (int i = 0; i < r.counts().length; i++) report.append(String.format(Locale.ROOT, "| %d | R%d | %d | %.2f |%n", o.run(), i + 1, r.counts()[i], r.received() == 0 ? 0 : 100.0 * r.counts()[i] / r.received()));
        }
        report.append("\n## Графики\n\n");
        List<Summary> required = summary.stream().filter(s -> s.name().startsWith("scenario-")).toList();
        draw(output, "scenario-time.png", "Время доставки в обязательных сценариях", "Номер сценария", "Секунды", required, s -> s.seconds(), s -> Integer.toString(s.example().scenario()));
        report.append("![Время сценариев](scenario-time.png)\n\n");
        List<Summary> receiverStudy = summary.stream().filter(s -> s.name().startsWith("receivers-")).toList();
        if (!receiverStudy.isEmpty()) {
            draw(output, "receivers-throughput.png", "Число получателей и пропускная способность", "Количество получателей", "Сообщений/с", receiverStudy, Summary::throughput, s -> Integer.toString(s.example().receivers()));
            draw(output, "receivers-deviation.png", "Отклонение долей от равномерного распределения", "Количество получателей", "Процентные пункты", receiverStudy, Summary::deviation, s -> Integer.toString(s.example().receivers()));
            report.append("![Получатели и пропускная способность](receivers-throughput.png)\n\n![Отклонение долей](receivers-deviation.png)\n\n");
        }
        List<Summary> sizes = summary.stream().filter(s -> s.name().startsWith("bytes-")).toList();
        if (!sizes.isEmpty()) {
            draw(output, "size-messages.png", "Размер сообщения и скорость доставки сообщений", "Размер сообщения, байт (категории)", "Сообщений/с", sizes, Summary::throughput, s -> Integer.toString(s.example().payloadBytes()));
            draw(output, "size-bytes.png", "Размер сообщения и скорость передачи данных", "Размер сообщения, байт (категории)", "МиБ/с", sizes, s -> s.byteRate() / (1024 * 1024), s -> Integer.toString(s.example().payloadBytes()));
            report.append("![Размер и msg/s](size-messages.png)\n\n![Размер и МиБ/с](size-bytes.png)\n\n");
        }
        List<Summary> nagle = summary.stream().filter(s -> s.name().startsWith("nodelay-")).toList();
        if (!nagle.isEmpty()) {
            draw(output, "nodelay.png", "Влияние TCP_NODELAY на время доставки", "TCP_NODELAY на клиенте", "Секунды", nagle, Summary::seconds, s -> s.example().tcpNoDelay() ? "Включён" : "Выключен");
            report.append("![TCP_NODELAY](nodelay.png)\n\n");
        }
        report.append("## Выводы и ограничения\n\n");
        long failures = rows.stream().filter(o -> !o.result().passed()).count();
        report.append("Всего запусков: ").append(rows.size()).append(". Неуспешных: ").append(failures).append(". Полнота проверяется по множествам ожидаемых и принятых ID, отдельно считаются дубликаты, неизвестные ID и повреждённые тексты. Одного равенства счётчиков недостаточно.\n\n");
        if (!receiverStudy.isEmpty()) {
            Summary best = receiverStudy.stream().filter(Summary::passed).max(Comparator.comparingDouble(Summary::throughput)).orElse(null);
            if (best != null) report.append(String.format(Locale.ROOT, "В этой серии наибольшая медианная скорость получена при числе получателей %d: %.1f сообщений/с. Это наблюдение локального стенда, не универсальный оптимум.\n\n", best.example().receivers(), best.throughput()));
        }
        if (sizes.size() > 1) {
            Summary small = sizes.getFirst(), large = sizes.getLast();
            report.append(String.format(Locale.ROOT, "При размере %d байт получено %.1f сообщений/с и %.2f МиБ/с; при %d байт — %.1f сообщений/с и %.2f МиБ/с. Крупное сообщение передаётся дольше, но накладные расходы приходятся на большее число байтов.\n\n", small.example().payloadBytes(), small.throughput(), small.byteRate() / 1048576, large.example().payloadBytes(), large.throughput(), large.byteRate() / 1048576));
        }
        if (nagle.size() == 2) {
            for (Summary s : nagle) report.append(String.format(Locale.ROOT, "TCP_NODELAY %s: медиана %.6f с, диапазон %.6f–%.6f с. ", s.example().tcpNoDelay() ? "включён" : "выключен", s.seconds(), s.min(), s.max()));
            report.append("Это сравнение пяти измерений на loopback; небольшая разница не доказывает преимущество настройки для любой сети.\n\n");
        }
        report.append("В сценарии 2 до закрытия R1 разрешено только одно ещё не учтённое сообщение: отправитель ждёт CountDownLatch предыдущего ID, открываемый после обновления всех счётчиков получателя. После 250-го сообщения R1 закрывается и открывает firstClosed. Затем отправитель продолжает. Это сохраняет порядок задания и предотвращает уход дополнительных сообщений в фоновый буфер R1. Это синхронизация теста внутри одного процесса, а не ACK сетевого протокола. Сценарий 2 нельзя напрямую сравнивать со свободной отправкой по msg/s.\n\n")
                .append("Обнаруженная гонка штатного отключения исправлена: клиент-получатель выполняет shutdownOutput и ограниченно ждёт ответный EOF, а LocalBroker исключает соединение из распределения до закрытия своей стороны. После ожидания клиент в любом случае закрывает сокет. Это завершение TCP, а не новая команда или ACK доставки.\n\n")
                .append("Без ACK успешная запись в TCP не доказывает обработку сообщения приложением. При произвольном обрыве после успешной записи возможна потеря, а при повторной отправке — дубликат. Поэтому гарантия exactly-once при аварии и безусловное переподключение не заявляются. Условие сценария 2 для неконтролируемой быстрой отправки требует уточнения у преподавателя.\n\n")
                .append("LocalBroker использует round-robin. Близкие доли на нём отражают его алгоритм, а не доказательство равномерности любого брокера. Тест с внешним сервером использует тот же клиент и не меняет ожидаемые значения.\n\n")
                .append("Увеличение числа получателей не обязано ускорять брокер: есть общий диспетчер, блокировки, TCP, переключения потоков. При малых сообщениях значимы системные вызовы, заголовки и flush. Крупные сообщения амортизируют эти расходы, но требуют больше времени на одну доставку. Фактическая зависимость видна на графиках; монотонный рост не предполагается.\n\n")
                .append("В опыте TCP_NODELAY меняется только опция клиентских сокетов. Вспомогательный сервер всегда использует TCP_NODELAY=true. Flush выталкивает Java-буфер, но не отключает алгоритм Нейгла. Loopback не воспроизводит задержку и потери сети VPS.\n\n")
                .append("Ответы на все 12 вопросов: ../../docs/ВОПРОСЫ.md. Проверка пунктов задания: ../../docs/ЧЕКЛИСТ.md.\n");
        Files.writeString(output.resolve("ОТЧЁТ.md"), report, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("ОТЧЁТ.txt"), report.toString().replaceAll("!\\[[^]]*]\\([^)]*\\)", "(график см. в PNG-файле рядом)"), StandardCharsets.UTF_8);
    }

    private static String quote(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }
    private static String countRange(List<ScenarioRunner.Result> values, java.util.function.ToIntFunction<ScenarioRunner.Result> metric) {
        var stats = values.stream().mapToInt(metric).summaryStatistics();
        return stats.getMin() == stats.getMax() ? Integer.toString(stats.getMin()) : stats.getMin() + "–" + stats.getMax();
    }
    private static String displayName(ScenarioRunner.Result result) {
        String name = result.name();
        if (name.startsWith("scenario-")) return "Сценарий " + result.scenario();
        if (name.startsWith("receivers-")) return "Получателей: " + result.receivers();
        if (name.startsWith("bytes-")) return "Размер: " + result.payloadBytes() + " байт";
        if (name.startsWith("nodelay-")) return "TCP_NODELAY: " + (result.tcpNoDelay() ? "включён" : "выключен");
        if (name.equals("load-4x4")) return "Нагрузка: 4 отправителя, 4 получателя";
        return name;
    }
    private static double median(double[] values) {
        java.util.Arrays.sort(values);
        int n = values.length;
        return n % 2 == 0 ? (values[n / 2 - 1] + values[n / 2]) / 2 : values[n / 2];
    }

    private static String graphNumber(double value) {
        if (value == 0) return "0";
        return value >= 1000 ? String.format(Locale.ROOT, "%,.0f", value).replace(',', ' ')
                : String.format(Locale.ROOT, value < 1 ? "%.3f" : "%.2f", value);
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
            g.setColor(Color.BLACK); g.drawString(graphNumber(max * i / 5), 38, y + 5);
        }
        g.drawLine(left, top, left, bottom); g.drawLine(left, bottom, right, bottom);
        g.drawString(yLabel, 20, 52); g.drawString(xLabel, 330, 475);
        for (int i = 0; i < data.size(); i++) {
            Summary s = data.get(i);
            int x = data.size() == 1 ? (left + right) / 2 : left + 30 + (right - left - 60) * i / (data.size() - 1);
            int y = bottom - (int) ((bottom - top) * value.applyAsDouble(s) / max);
            g.setColor(s.passed() ? new Color(50, 90, 160) : Color.RED);
            g.fillRect(x - 22, y, 44, Math.max(1, bottom - y));
            g.setColor(Color.BLACK);
            String text = label.apply(s); g.drawString(text, x - g.getFontMetrics().stringWidth(text) / 2, bottom + 24);
            String number = graphNumber(value.applyAsDouble(s));
            g.drawString(number, x - g.getFontMetrics().stringWidth(number) / 2, y - 10);
        }
        g.dispose(); ImageIO.write(image, "png", output.resolve(filename).toFile());
    }
}
