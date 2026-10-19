# Verificación de la entrega

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
