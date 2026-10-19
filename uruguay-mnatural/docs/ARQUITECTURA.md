# Arquitectura y reglas críticas

El módulo platform usa MVC en capas con Thymeleaf y Spring JDBC. El simulador es otro proceso Spring Boot y otra base de datos. No hay transacción distribuida: las retenciones persistidas, la idempotencia y la conciliación resuelven fallos entre sistemas.

## Reserva

`ReservationTransactions.reserve` abre una transacción corta. Bloquea primero al turista para serializar claves repetidas y luego la instancia para serializar cupos. Una clave reutilizada con cuerpo distinto produce conflicto. Suma personas de reservas comprometidas, valida fecha y cantidad, copia precio y condiciones y crea reserva y pago local. La transacción termina antes de llamar al servicio externo.

`Payments.synchronize` consulta por paymentId o referencia. Si la operación no existe, la crea con la misma referencia. `ReservationTransactions.apply` bloquea instancia y reserva, comprueba referencia, importe, moneda, vencimiento y aprobación y cambia el estado una sola vez. Un evento repetido no confirma de nuevo. El simulador impide pasar de un estado terminal a otro y no aprueba después del vencimiento.

Ante un timeout, `uncertain` conserva lugares. La tarea de conciliación consulta cada 30 segundos. La liberación exige REJECTED, CANCELLED o EXPIRED remoto; el simple paso de diez minutos no implica que un pago desconocido haya fallado. Al vencer sin respuesta se prefiere retener temporalmente a vender un lugar posiblemente pagado.

## Modelo relacional

User 1 a 0..1 Venture. Venture 1 a N Activity. Activity N a N Category y N a N EquipmentType (con modalidad). Activity 1 a N ActivityInstance. User turista 1 a N Reservation. Instance 1 a N Reservation. Reservation 1 a 1 Payment y 1 a 0..1 Review. Department 1 a N Locality. Los snapshots conservan las condiciones de compra.

Las migraciones son la definición exacta del esquema. El proyecto no usa borrado en cascada sobre reservas y pagos. Las instancias referenciadas se desactivan; los catálogos utilizados se desactivan. Las actividades se retiran del portal con isPublished=false.

## Seguridad

El portal usa sesiones Spring Security, BCrypt y CSRF. Los controladores y servicios comprueban rol y dueño. La API pública solo expone proyecciones. La aprobación JSON tiene una cadena HTTP Basic sin sesión; el formulario HTML usa sesión y CSRF. El webhook exige un secreto y vuelve a consultar la operación, sin confiar en el estado recibido. El simulador exige X-API-Key en sus rutas JSON y protege sus formularios de checkout con CSRF.

## Tiempo y pruebas

Los servicios usan Clock para sus decisiones. Los filtros diarios y mensuales calculan límites en America/Montevideo; SQL usa intervalos [inicio, fin). Las pruebas rápidas ejecutan migraciones y páginas completas en H2 modo PostgreSQL; una segunda suite reutiliza los escenarios con PostgreSQL real mediante Testcontainers.

## Diferencias explicitadas respecto de la especificación

Java 21 es el runtime Docker, pero el código compila para Java 17 para facilitar su uso. Se eligió JDBC en lugar de JPA; ambas opciones cumplen MVC y PostgreSQL. Los manuales y el modelo de datos están en Markdown. No se incluye un PDF descargable de constancia: se ofrece la página imprimible, que el navegador permite guardar como PDF. El clima opcional no está implementado. No se aporta un catálogo nacional completo de localidades ni se declara una medición de SLA sin ejecutarla.
