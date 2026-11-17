# Позднейший запуск сервера на VPS

Для сдачи VPS не требуется. Сервер брокера можно запускать локально. Сейчас VPS не предоставлен, поэтому эти команды — инструкция, а не отчёт о выполненном развёртывании.

## 1. Подготовить JDK и файл

На компьютере собрать `mvn package` или `./build.ps1`. Будет создан `target/lab4-broker-client-1.0.jar`. На VPS нужен JDK/JRE 21 или новее. Способ установки зависит от ОС; после установки проверить:

```text
java -version
```

Файл JAR переносится на Linux VPS, например:

```powershell
scp target/lab4-broker-client-1.0.jar USER@VPS_IP:~/lab4-broker-client-1.0.jar
```

`USER` и `VPS_IP` заменить своими значениями. Лабораторную №2 загружать не нужно. Сам JAR содержит и клиент, и вспомогательный сервер: роль выбирается аргументами. Сервер и клиент запускаются отдельными процессами.

## 2. Проверить сервер вручную

После SSH-подключения на VPS:

```sh
java -Xms64m -Xmx512m -jar ~/lab4-broker-client-1.0.jar --server --bind 127.0.0.1 --port 1111
```

Пока терминал открыт, сервер работает. Ctrl+C запускает обработчик завершения, который закрывает слушающий сокет, соединения и рабочие потоки. Это сервер в памяти: перезапуск удаляет очереди.

## 3. Подключиться с компьютера через SSH-туннель

На компьютере открыть отдельный терминал:

```powershell
ssh -N -L 1112:127.0.0.1:1111 USER@VPS_IP
```

В другом терминале, в папке проекта:

```powershell
java -jar target/lab4-broker-client-1.0.jar --host 127.0.0.1 --port 1112 --full --out results/vps-run
```

Здесь 1112 — локальный вход туннеля, 1111 — порт брокера на VPS. Не добавлять `--local`: иначе эксперимент пойдёт на встроенном брокере, а не на VPS. Сохранить CSV и environment.txt отдельно от results/local. Туннель добавляет шифрование и свои накладные расходы: это необходимо указать при сравнении скоростей.

## 4. При необходимости оставить сервер работать

Можно оформить сервис systemd на Linux. Пример, который нужно адаптировать под реального пользователя и пути:

```ini
[Unit]
Description=Lab 4 auxiliary TCP broker
After=network.target

[Service]
Type=simple
User=lab4
WorkingDirectory=/opt/lab4
ExecStart=/usr/bin/java -Xms64m -Xmx512m -jar /opt/lab4/lab4-broker-client-1.0.jar --server --bind 127.0.0.1 --port 1111
Restart=on-failure
TimeoutStopSec=10

[Install]
WantedBy=multi-user.target
```

Перед установкой создать пользователя `lab4`, каталог `/opt/lab4` и поместить туда JAR. Путь Java проверить через `command -v java`. Конфигурацию сохранить как `/etc/systemd/system/lab4-broker.service`, затем администратор выполняет:

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now lab4-broker
sudo systemctl status lab4-broker
journalctl -u lab4-broker -n 50
```

## Прямое TCP-подключение

Лишь если действительно нужен открытый порт, можно указать `--bind 0.0.0.0` и разрешить порт в firewall VPS только для IP клиента. Тогда клиент использует `--host VPS_IP --port 1111`. Базовый учебный протокол не содержит логина или TLS. SSH-туннель позволяет оставить брокер на 127.0.0.1 без публикации учебного сервиса в интернете.

Если преподаватель выдаст собственный сервер, разворачивается он, а вспомогательный LocalBroker заменяется без изменения BrokerClient.
