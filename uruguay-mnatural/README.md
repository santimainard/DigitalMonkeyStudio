# Uruguay Mnatural

Aplicación del Proyecto TIC 2026: portal de turismo, gestión de prestadores, aprobación administrativa, reservas con cupos, reseñas y pagos externos simulados.

## Inicio en Windows con Docker Desktop

1. Instalá Docker Desktop y dejalo iniciado, usando contenedores Linux.
2. Descomprimí el proyecto y abrí PowerShell dentro de la carpeta `uruguay-mnatural`.
3. Generá las claves locales y levantá los servicios:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\setup.ps1
docker compose up --build
```

4. Cuando Spring termine de iniciar, abrí **http://localhost:8080**. El simulador está en **http://localhost:8081/checkout/{paymentId}** y se abre desde la reserva.

El primer inicio descarga imágenes y dependencias. No hace falta instalar Java, Maven o PostgreSQL por separado con esta opción. `setup.ps1` solo crea `.env` con valores aleatorios; no modifica políticas permanentes de PowerShell. No compartas `.env` ni lo subas a Git.

En macOS/Linux: `sh scripts/setup.sh` y `docker compose up --build`.

## Cuentas

| Rol | Correo | Contraseña local |
| --- | --- | --- |
| Administrador | El valor ADMIN_EMAIL de .env | ADMIN_PASSWORD de .env |
| Prestador de demostración | prestador@example.test | DEMO_PASSWORD de .env |
| Turista de demostración | turista@example.test | DEMO_PASSWORD de .env |

Las cuentas de demostración se crean solo con `DEMO_DATA=true`. Son ficticias. El alta inicial del administrador no vuelve a cambiar su contraseña en cada reinicio: editar `.env` después no cambia usuarios existentes.

## Qué incluye

- Spring Boot 3.5.16 y Spring Framework mediante sus starters; compilación compatible con Java 17 o superior, contenedores con Java 21.
- Thymeleaf con formularios CSRF, sesión, roles y autorización por propietario.
- PostgreSQL, Spring JDBC, migraciones Flyway y transacciones explícitas. Se usa JDBC en lugar de JPA para que los bloqueos y consultas sean visibles al estudiar el código.
- Prestadores físicos/jurídicos; aprobación por ADMIN; actividades con múltiples categorías y equipamiento; cronograma y cupos limitados o ilimitados.
- Búsqueda combinada, detalle, resumen previo a reservar, constancia imprimible, historial y reseña después del fin con pago aprobado.
- Retención de 10 minutos, control de concurrencia, idempotencia, consulta remota y conciliación cada 30 segundos.
- Servicio externo separado que persiste operaciones en su propia PostgreSQL y simula aprobación, rechazo, cancelación, timeout y callbacks repetidos.
- Las cinco operaciones REST exigidas; contrato en `docs/openapi.yaml`, también publicado en `/openapi.yaml`.
- Pruebas unitarias y de integración. Consultá `docs/VERIFICACION.md` para conocer exactamente lo ejecutado.

## Desarrollo en IntelliJ o terminal

Abrí el `pom.xml` raíz como proyecto Maven. Usá JDK 17 o 21. Maven Wrapper permite compilar sin Maven instalado.

```sh
./mvnw clean verify
docker compose up -d database payments-database
```

En Windows reemplazá `./mvnw` por `.\mvnw.cmd`. Para ejecutar sin contenedores de aplicación, configurá las variables de entorno en cada Run Configuration:

**PlatformApplication:** `DATABASE_URL=jdbc:postgresql://localhost:5432/tourism`, `DATABASE_USER=tourism`, `DATABASE_PASSWORD`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `PAYMENT_API_KEY`, `PAYMENT_WEBHOOK_TOKEN`, `PAYMENT_BASE_URL=http://localhost:8081`, `PAYMENT_CALLBACK_URL=http://localhost:8080/api/v1/payment-events`. Para datos de prueba: `DEMO_DATA=true` y `DEMO_PASSWORD`.

**PaymentSimulatorApplication:** `DATABASE_URL=jdbc:postgresql://localhost:5433/payments`, `DATABASE_USER=payments`, `DATABASE_PASSWORD` igual a PAYMENTS_DATABASE_PASSWORD, las mismas `PAYMENT_API_KEY` y `PAYMENT_WEBHOOK_TOKEN`, `PAYMENT_CALLBACK_URL=http://localhost:8080/api/v1/payment-events`, `PAYMENT_PUBLIC_URL=http://localhost:8081`, `PLATFORM_PUBLIC_URL=http://localhost:8080`.

`.env` es leído por Docker Compose, no automáticamente por Java. En ejecución local cargá esas variables en IntelliJ o en tu terminal. También se pueden ejecutar los JAR producidos en `platform/target/` y `payment-simulator/target/`.

## Pruebas

`./mvnw verify` ejecuta pruebas de reglas, seguridad, migraciones, vistas Thymeleaf, reservas, idempotencia, reseñas y simulador. Las pruebas rápidas usan H2 en modo PostgreSQL únicamente bajo `src/test`; la aplicación entregada exige PostgreSQL. `PostgresIntegrationTest` repite el flujo con PostgreSQL 16 mediante Testcontainers. Si no hay Docker, esa clase se omite y Maven informa los casos skipped; no debe interpretarse como validación de PostgreSQL.

## Organización

El contrato funcional completo está en `docs/REQUISITOS.md`; los recorridos por rol, en `docs/MANUALES.md`, y los resultados de pruebas, en `docs/VERIFICACION.md`.

```text
platform/              Aplicación web y API
  config/              Seguridad, alta inicial y utilidades de presentación
  controller/          Controladores MVC/REST y errores
  dto/                 Entradas y validaciones
  domain/              Reglas de reserva y errores de negocio
  repository/          Acceso SQL parametrizado
  service/             Reglas, transacciones y coordinación de pagos
  integration/         Cliente HTTP del servicio externo
payment-simulator/     Aplicación Spring Boot independiente
docs/                  Requisitos, OpenAPI, manuales, arquitectura y pruebas
scripts/               Preparación local
```

## Alcance y decisiones

Se mantiene el alcance de la especificación: UYU, precio por participante, un emprendimiento por prestador, una reseña por reserva, sin cancelaciones de reservas pagadas ni devoluciones. No se registra asistencia ni datos de acompañantes. Clima queda desactivado y sin integración implementada, como extensión opcional. Los datos geográficos incluyen los 19 departamentos y una selección de localidades, no todas las localidades del país. La aprobación del administrador es la operación de verificación de emprendimiento de la API.

La demora remota conserva cupos hasta que el resultado sea comprobable. Si se inicia una compra dentro del plazo, se permite terminarla durante su retención aunque cruce el comienzo. Se utiliza America/Montevideo para fechas visibles y filtros, y UTC para instantes persistidos.

## Operación

`docker compose stop` detiene y conserva datos. `docker compose up -d` los vuelve a iniciar. `docker compose logs -f platform payments` muestra los registros. Los volúmenes mantienen los datos tras recrear contenedores. `docker compose down -v` **borra ambas bases**; usarlo solo si se quiere reiniciar la demostración.

Los puertos publicados están ligados a localhost. El despliegue público requiere HTTPS, cookies secure y variables propias; los botones del simulador son para demostración. La consola no imprime contraseñas ni secretos. El diseño visual es una propuesta para presentar al docente cliente.

## Control de versiones

El paquete incluye `.gitignore` para excluir credenciales, resultados y configuración del IDE. Para iniciar tu repositorio después de descomprimirlo:

```sh
git init
git add .
git commit -m "Implementación inicial de Uruguay Mnatural"
```

Configurá tu nombre y correo en Git si todavía no lo hiciste. No agregues `.env` al repositorio.
