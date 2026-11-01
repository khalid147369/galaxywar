# Odyssey Game Server

Servidor multijugador independiente del cliente libGDX. Usa Java 17, Gradle,
Netty y PostgreSQL. El protocolo es TCP con mensajes JSON delimitados por una
nueva línea; no es un servidor HTTP ni WebSocket.

## Alcance implementado

- Salas con código y jugadores invitados (sin cuentas ni contraseñas).
- Estado de sala autoritativo en el servidor: flotas, naves, planetas, dueños,
  HP, proyectiles, edificios y torretas.
- Validación de órdenes de movimiento, cambios de cuadrícula, disparos,
  reclutamiento, reclamación de planetas neutrales, construcción/retirada de
  edificios, producción, torretas y asedio de planetas rivales.
- Snapshots periódicos para los clientes conectados.
- Token de reconexión aleatorio; se guarda su hash, y el cliente intenta
  reconectar mientras la sesión siga dentro de `RECONNECT_TTL_SECONDS`.
- Persistencia de salas y jugadores invitados con PostgreSQL y migraciones
  Flyway.
- TLS TCP opcional en el servidor mediante certificado y clave PEM.

Los edificios, los recursos, la producción planetaria, la colocación y el HP de
torretas, su daño y la conquista por asedio son autoritativos y se sincronizan
mediante snapshots. La batalla planetaria online comparte sus resultados, pero
las trayectorias visuales locales, apuntado y animaciones no son una simulación
de posiciones de combate autoritativa equivalente al modo individual.

## Ejecutar localmente

Requisitos: Docker Compose. Desde esta carpeta, en PowerShell:

```powershell
Copy-Item .env.example .env
```

Edita `.env` y sustituye `DATABASE_PASSWORD=replace_me` por una contraseña
local que elijas. No subas el `.env` al repositorio.

```powershell
docker compose up --build
```

El servidor escucha en `0.0.0.0:$PORT` (por defecto, `8080`) y PostgreSQL queda
en el contenedor `database`. Para pararlo, usa `Ctrl+C`; los datos de
PostgreSQL permanecen en el volumen `odyssey-postgres`.

Para ejecutar pruebas y compilar sin Docker:

```powershell
..\gradlew.bat -p . test
```

Configura el cliente con `GAME_SERVER_HOST=127.0.0.1` y
`GAME_SERVER_PORT=8080` (o con las propiedades JVM
`game.server.host` y `game.server.port`). Para TLS, activa
`GAME_SERVER_TLS=true` en el cliente y `TLS_ENABLED=true` en el servidor.

## Variables de entorno

| Variable | Uso | Valor local predeterminado |
|---|---|---|
| `PORT` | Puerto TCP de escucha | `8080` |
| `TLS_ENABLED` | Habilita TLS TCP en Netty | `false` |
| `TLS_CERTIFICATE_PATH` | Ruta al certificado PEM cuando TLS está habilitado | sin valor |
| `TLS_PRIVATE_KEY_PATH` | Ruta a la clave privada PEM | sin valor |
| `TLS_PRIVATE_KEY_PASSWORD` | Contraseña de clave privada, si aplica | sin valor |
| `TLS_CERTIFICATE_BASE64` | Certificado PEM (incluye cadena) codificado en Base64 | sin valor |
| `TLS_PRIVATE_KEY_BASE64` | Clave privada PEM codificada en Base64 | sin valor |
| `DATABASE_HOST` | Host PostgreSQL | `localhost` en `.env.example` |
| `DATABASE_PORT` | Puerto PostgreSQL | `5432` |
| `DATABASE_NAME` | Nombre de la base de datos | `odyssey` |
| `DATABASE_USER` | Usuario PostgreSQL | `odyssey` |
| `DATABASE_PASSWORD` | Contraseña PostgreSQL | obligatoria; completar manualmente |
| `DATABASE_SSLMODE` | Modo SSL JDBC (`disable`, `prefer`, `require`, etc.) | `prefer` |
| `DATABASE_POOL_SIZE` | Máximo de conexiones del pool | `8` |
| `ROOM_MAX_PLAYERS` | Jugadores máximos por sala | `2` |
| `TICK_RATE_HZ` | Frecuencia de simulación | `20` |
| `RECONNECT_TTL_SECONDS` | Tiempo máximo para volver a entrar | `300` |
| `LOG_LEVEL` | Nivel de `java.util.logging` (`INFO`, `WARNING`, `SEVERE`, `FINE`, etc.) | `INFO` |

El ejemplo no contiene credenciales reales. Usa secretos del proveedor para la
contraseña y la clave privada.

## Desplegar el servidor en Railway

Despliega **solo la carpeta `game-server`** como servicio Docker. No despliegues
el proyecto raíz ni el cliente en Railway.

1. Sube el repositorio a GitHub.
2. En Railway, crea un proyecto y añade un servicio PostgreSQL.
3. Añade un servicio desde el repositorio GitHub. En sus ajustes establece
   **Root Directory** como `game-server`. Railway detectará el `Dockerfile` en
   esa carpeta; deja sin configurar la ruta personalizada de Dockerfile.
4. Añade estas variables al servicio del juego (ajusta `Postgres` si el nombre
   del servicio PostgreSQL es otro):

   ```text
   PORT=8080
   DATABASE_HOST=${{Postgres.PGHOST}}
   DATABASE_PORT=${{Postgres.PGPORT}}
   DATABASE_NAME=${{Postgres.PGDATABASE}}
   DATABASE_USER=${{Postgres.PGUSER}}
   DATABASE_PASSWORD=${{Postgres.PGPASSWORD}}
   DATABASE_SSLMODE=prefer
   ROOM_MAX_PLAYERS=2
   TICK_RATE_HZ=20
   RECONNECT_TTL_SECONDS=300
   TLS_ENABLED=true
   ```

   Railway sustituye las referencias a variables del servicio PostgreSQL. El
   tráfico entre servicios usa la red privada de Railway; `prefer` permite SSL
   si PostgreSQL lo ofrece. Si tu base de datos exige SSL, usa `require`. No
   publiques contraseñas en el repositorio ni en el cliente.
5. El servidor escucha en `0.0.0.0:PORT`. En los ajustes del servicio abre
   **Networking → TCP Proxy** y crea un proxy dirigido al puerto interno `8080`.
   Railway te dará un hostname y un puerto público; anótalos. El protocolo es
   TCP, no HTTP, por lo que un dominio HTTPS generado para tráfico web no sirve
   como endpoint del juego.
6. El proxy TCP publica bytes TCP; no se debe asumir que termina TLS. Para
   mantener cifrado hasta el servidor, usa un dominio propio apuntando al
   hostname del proxy TCP y configura en el servicio un certificado PEM válido
   para ese dominio y su clave privada como secretos `TLS_CERTIFICATE_BASE64` y
   `TLS_PRIVATE_KEY_BASE64`. El certificado debe incluir la cadena y ser
   aceptado por los almacenes de confianza del sistema del jugador. Para
   codificar ambos archivos localmente en PowerShell:

   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes(".\fullchain.pem"))
   [Convert]::ToBase64String([IO.File]::ReadAllBytes(".\privkey.pem"))
   ```

   Copia los resultados directamente a las variables secretas de Railway, no
   a archivos del repositorio ni al chat. Mantén y renueva el certificado antes
   de que caduque. El cliente valida la cadena y el hostname TLS.
7. Espera a que Railway marque el despliegue como activo. Si falla el arranque,
   comprueba primero las variables `DATABASE_*`, la migración Flyway y los
   logs del servicio.

Si todavía no tienes un certificado válido para un dominio controlado por ti,
no configures el cliente con `TLS=false` para partidas públicas: el token de
reconexión y los comandos viajarían sin cifrar. Puedes probar primero en local
con Docker Compose y TLS desactivado, pero restringe ese puerto a tu máquina o
red de desarrollo.

## Conectar y empaquetar el cliente

Edita [online.properties](../core/src/main/resources/online.properties) **antes
de empaquetar el cliente**. Usa el hostname y el puerto público TCP que Railway
asignó (no el puerto interno `8080` si el proxy asignó otro):

```properties
server.host=tu-dominio-tcp.example.com
server.port=PUERTO_PUBLICO_RAILWAY
server.tls=true
```

Después compila la distribución cliente desde la raíz del proyecto:

```powershell
.\gradlew.bat :lwjgl3:dist
```

Distribuye el JAR/launcher generado desde `lwjgl3/build`; no distribuyas
`game-server`, `game-server/build` ni el archivo `.env`. En la configuración
actual del repositorio solo están incluidos `core` y `lwjgl3`; todavía no hay
módulo Android configurado.

Para Windows/Linux, el cliente solo incluye libGDX, Gson y sus dependencias de
escritorio. Para actualizar el endpoint después de publicar una versión,
cambia las tres líneas de `online.properties` y vuelve a generar/distribuir el
cliente. Para desarrollo local puedes sobreescribirlo con `GAME_SERVER_HOST`,
`GAME_SERVER_PORT`, `GAME_SERVER_TLS` o las propiedades JVM equivalentes.

El botón **Crear sala online** genera un código; el otro jugador debe introducir
ese código en **Unirse con código**. El botón **Reanudar última sala** utiliza
el token guardado localmente en las preferencias de la aplicación.

## Protocolo básico

Cada línea enviada es un objeto JSON UTF-8. El cliente comienza con:

```json
{"type":"create_room","name":"Pilot"}
```

Para entrar se envía `{"type":"join_room","name":"Rival","roomCode":"ABC234"}`.
El servidor responde con `type: "joined"`, `playerId`, `roomCode`,
`reconnectToken` y el snapshot inicial. Los comandos se envían como
`{"type":"command","payload":{"action":"move",...}}`; las respuestas de error
incluyen `type: "error"` y un mensaje legible. Los snapshots tienen
`type: "snapshot"` y contienen la revisión, el universo, las flotas y los
proyectiles.

## Separación de la exportación del cliente

`game-server` es un proyecto Gradle independiente y no aparece en los módulos
del Gradle raíz del juego. El cliente solo añade Gson para el protocolo JSON;
no incorpora Netty, PostgreSQL ni clases del servidor. Publica el artefacto
habitual de libGDX desde la raíz y despliega `game-server` por separado.
