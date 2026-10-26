# Verificación de la entrega

## Verificación de la actualización de cancelaciones y permisos

La ejecución en el entorno de desarrollo en la nube pasó **55 pruebas: 0 fallos, 0 errores y 0 omitidas**, con JDK 21, Maven 3.9.9 y Docker. Se ejecutaron BookingRulesTest (5), PaymentHttpTest (2), PlatformIntegrationTest con H2 (19), PostgresIntegrationTest con PostgreSQL 16 real (19), RegistrationChecksTest (3) y SimulatorTest (7).

La cobertura de registro también prueba, para turistas y prestadores, confirmación de contraseña vacía, distinta y coincidente; comprueba que no haya altas inválidas ni contraseñas reflejadas en el HTML de error.

La cobertura agregada comprueba bloqueo del prestador pendiente/denegado, motivo obligatorio, denegación definitiva sin nueva solicitud, restricción de Explorar, snapshots de reembolso, límites de anticipación, propiedad, cancelación de instancia, cupos retenidos durante conciliación, reintentos, rechazo de un resultado remoto desactualizado, elevación a devolución total y aprobación de pago concurrente con cancelación.

`scripts/http-smoke.py` ejecuta ambos servicios HTTP independientes con H2 de pruebas. Además del flujo original, prueba la política conservada por reservas anteriores, reembolso parcial del 35 %, reembolso total, cancelación sin pago, indisponibilidad, respuesta de reembolso perdida, reintentos y cierre de una instancia por el prestador. No usa dinero real.

Para repetir: `sh ./mvnw verify` en Linux (o `.\mvnw.cmd verify` en Windows) con un JDK completo y Docker activo; luego `python3 scripts/http-smoke.py` (o `py scripts\http-smoke.py`). La plataforma y el simulador deben actualizarse juntos para disponer del nuevo contrato de cancelación. Flyway aplica V3/V4/V5 en tourism y V2 en payments, conservando los datos y las migraciones anteriores.

También se arrancaron los dos JAR sobre las bases PostgreSQL de desarrollo existentes: Flyway aplicó tourism V3/V4/V5 y payments V2, ambos servicios devolvieron salud UP y la API recuperó la actividad de demostración con sus instancias. No se borraron volúmenes.

El resto de este documento es el registro histórico de la entrega inicial; sus nueve omisiones no describen esta nueva ejecución. La inspección visual de los paneles se detalla en Rediseño de Mi espacio. El empaquetado de las aplicaciones en imágenes Docker queda fuera de esta comprobación; las pruebas de PostgreSQL sí se ejecutaron mediante Docker.


Fecha: 5 de octubre de 2026. Compilación ejecutada con OpenJDK 17 y Maven 3.9.9.

## Resultado de Maven

`mvn verify`: **BUILD SUCCESS** en ambos módulos. Se generaron los dos JAR ejecutables de Spring Boot. Total: **21 pruebas ejecutadas correctamente, 0 fallos, 0 errores y 9 omitidas**.

| Suite | Correctas | Omitidas | Cobertura principal |
| --- | ---: | ---: | --- |
| BookingRulesTest | 5 | 0 | Plazo inclusivo, cantidades, cupos, importes decimales y condición para reseñar |
| PlatformIntegrationTest | 9 | 0 | Migraciones, registro, autorización, CSRF, vistas Thymeleaf, API, reservas concurrentes, idempotencia, snapshots y reseñas |
| PaymentHttpTest | 2 | 0 | Cliente HTTP real, cabeceras, contrato JSON y diferencia entre operación inexistente y servicio indisponible |
| SimulatorTest | 5 | 0 | Idempotencia, estados terminales, vencimiento, autenticación, checkout y recuperación del simulador |
| PostgresIntegrationTest | 0 | 9 | Repite la integración de la plataforma en PostgreSQL 16 mediante Testcontainers |

Las pruebas rápidas utilizan H2 en modo PostgreSQL como dependencia de pruebas. **No equivalen a una ejecución sobre PostgreSQL real.** La suite PostgreSQL fue omitida porque el entorno de elaboración no dispone de Docker. Tampoco se ejecutó `docker compose up` aquí. Estos son los dos controles que deben completarse en un equipo con Docker antes de considerar validada esa configuración.

## Recorrido entre servicios independientes

Se iniciaron la plataforma y el simulador en dos procesos Java, con puertos distintos y bases H2 independientes de prueba. Se verificó por HTTP real:

1. Inicio de sesión del turista y envío de formularios con CSRF.
2. Creación de una reserva para dos participantes e importe correcto.
3. Reintento con la misma clave: conserva la misma reserva.
4. Apertura del checkout y conservación de la sesión del turista al volver al portal.
5. Aprobación remota y dos callbacks iguales: una sola confirmación y ocupación correcta.
6. Bloqueo de reseñas antes del fin de la actividad.
7. Servicio de pagos indisponible: reserva incierta y conservación del cupo.
8. Recuperación mediante consulta por referencia.
9. Rechazo remoto con callback demorado: conciliación manual y liberación del cupo.

Este recorrido detectó y permitió corregir la colisión de cookies entre servicios en localhost. La plataforma utiliza `UM_SESSION` y el simulador `UM_PAY_SESSION`.

## Cómo repetirlo

Desde la raíz del proyecto, con JDK 17 o 21:

```sh
./mvnw verify
python3 scripts/http-smoke.py
```

En Windows: `.\mvnw.cmd verify` y `py scripts\http-smoke.py`. El recorrido HTTP requiere Python 3, sin paquetes adicionales, y los informes generados por Maven. Usa los puertos 18080 y 18081, bases efímeras y credenciales ficticias exclusivas de esa ejecución. Sus registros quedan en `target/tmp/`; los procesos se detienen al terminar.

Para ejecutar también PostgreSQL, iniciá Docker antes de `./mvnw verify` y comprobá que `PostgresIntegrationTest` ya no figure como omitida. Para comprobar el empaquetado Docker, seguí el inicio de README y realizá el recorrido de los manuales con sus bases PostgreSQL.

## Límites

Se comprobó el renderizado de las vistas mediante MockMvc; no se completó una inspección visual en navegador porque su ejecutable no estaba disponible y la descarga falló. No se realizaron ensayos de carga, auditoría de seguridad externa, mediciones de disponibilidad ni una validación con usuarios. Las pruebas de concurrencia cubren el escenario automatizado incluido; no demuestran por sí solas el comportamiento bajo cualquier carga. El simulador no procesa dinero real. Las decisiones opcionales y las diferencias de implementación están documentadas en ARQUITECTURA.md.

## Validaciones de registro y personería

Se verificaron errores de campo del servidor, rechazo de teléfonos con letras, correos inválidos, nombres con dígitos, confirmaciones de contraseña incorrectas y datos jurídicos incompletos, sin altas parciales. Persona física no guarda RUT ni razón social, aunque un cliente los envíe. Las denegaciones mantienen su motivo y no permiten nuevas solicitudes por la antigua ruta.

`scripts/registration-dom-test.cjs` comprobó ambos formularios con jsdom: mensajes inline, atributos de accesibilidad, filtrado de teléfono/RUT, contraseña por longitud UTF-8 y coincidencia, activación de campos jurídicos, exclusión de campos ocultos y bloqueo de envíos inválidos. Para repetir con la plataforma iniciada y Node.js 18 o superior:

```sh
npm install --prefix /tmp/um-dom --cache /tmp/um-npm-cache jsdom@26.1.0 --no-audit --no-fund
NODE_PATH=/tmp/um-dom/node_modules node scripts/registration-dom-test.cjs
```

Estas pruebas usan el HTML y JavaScript servidos por la aplicación. No equivalen a una inspección visual en navegador: la descarga de Chromium no estuvo disponible por la política de red del entorno. La migración V5 se aplicó sobre la base PostgreSQL de desarrollo existente y ambos servicios arrancaron correctamente.

## Rediseño de Mi espacio

Se comprobaron los resúmenes de cada cuenta, incluidos conteos globales independientes de la página del historial, próxima reserva confirmada, agenda del prestador y ausencia de datos operativos para prestadores no habilitados. Los indicadores usan datos persistidos y no sustituyen las validaciones de reserva/pago.

Se ejecutó `scripts/workspace-browser-test.cjs` con Playwright y Chromium del sistema sobre los dos servicios y PostgreSQL de desarrollo. Se capturaron los paneles de turista (sin próxima salida y con reserva confirmada), prestador, administrador y prestador pendiente/denegado en 1440×1000 y 390×844. Se revisaron las capturas y se verificó que no hubiera desbordamiento horizontal ni errores JavaScript. También se probaron los filtros administrativos y el motivo de denegación.

Para repetir con datos de demostración y ambas aplicaciones iniciadas:

```sh
CHROMIUM_PATH=/usr/bin/chromium node scripts/workspace-browser-test.cjs
```

Requiere el paquete Playwright disponible en Node.js y Chromium instalado. El script lee credenciales locales desde las variables de proceso o `.env`, sin imprimirlas. Es exclusivo de desarrollo local: crea dos prestadores ficticios para comprobar sus estados y una reserva de demostración que cancela al terminar. Las capturas quedan en `/tmp/um-workspaces`; no se versionan credenciales ni imágenes de las pruebas.

La comprobación visual anterior pendiente corresponde a la entrega inicial. Los paneles de esta actualización sí se inspeccionaron en un navegador real.
