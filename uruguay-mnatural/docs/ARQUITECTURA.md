# Arquitectura y reglas críticas

## Reglas vigentes de acceso y cancelación

Estas decisiones sustituyen el alcance anterior que excluía cancelaciones y devoluciones y permitía operar a prestadores pendientes.

- Al entrar se exige iniciar sesión o crear cuenta. El login lleva siempre a Mi espacio. Solo turistas acceden a Explorar (`/explore`) y al detalle de actividades; prestadores y administradores tienen sus paneles. La API de consulta externa conserva sus rutas públicas.
- El prestador pendiente o denegado solo ve su solicitud y puede salir. No puede crear/editar actividades, fechas, contacto ni consultar reservas, incluso enviando rutas directamente. El administrador habilita el emprendimiento, no cada actividad.
- La denegación exige motivo (hasta 2.000 caracteres) y es definitiva para ese emprendimiento. El prestador conserva acceso al motivo, pero no puede volver a solicitar verificación. No se permite denegar un emprendimiento ya verificado mediante este flujo.
- El prestador define por actividad la anticipación para reembolso total (minutos, de 0 a 525.600) y el porcentaje a devolver fuera de ese plazo (entero de 0 a 100). En el límite exacto de anticipación corresponde el 100 %. El porcentaje es **lo que se devuelve**, no la penalización.
- La reserva conserva una copia de esa política y su inicio original. Ediciones posteriores no alteran compras existentes. Actividades y reservas anteriores a esta actualización reciben la política sin penalización: 100 % antes del inicio; el prestador puede cambiarla para nuevas reservas.
- El turista cancela su propia reserva; el prestador cancela una instancia completa de su actividad y devuelve el 100 % a las reservas vigentes pagadas. Las solicitudes pendientes de cancelación del turista se elevan al 100 % si el prestador cancela la instancia antes de finalizar su conciliación. Las reservas ya canceladas no se reabren.
- Ambos pueden **solicitar** cancelación únicamente antes del comienzo. Al inicio exacto ya no se acepta una nueva solicitud. Los reintentos y la conciliación de una solicitud aceptada antes del inicio pueden completarse después.
- Los importes se calculan sobre el total pagado, en UYU y con dos decimales, redondeo HALF_UP. Una reserva sin pago se cancela sin reembolso; una aprobación concurrente se resuelve bajo el bloqueo del simulador antes de calcular la devolución.
- Se persiste la intención antes de llamar por HTTP. Si hay indisponibilidad o respuesta perdida, queda pendiente y la conciliación reintenta con la misma referencia. Se conservan cupos hasta verificar el resultado; la instancia cancelada se cierra inmediatamente a nuevas reservas. Nunca se duplican devoluciones.
- El cierre deja la reserva en PAYMENT_CANCELLED, conserva el importe original y registra actor, porcentaje, solicitud, finalización e importe devuelto. Un pago previamente aprobado queda REFUNDED localmente, incluso si el porcentaje es cero; el simulador conserva el pago APPROVED y registra la devolución acumulada por separado. Ninguna reserva con cancelación solicitada admite reseña.


El módulo platform usa MVC en capas con Thymeleaf y Spring JDBC. El simulador es otro proceso Spring Boot y otra base de datos. No hay transacción distribuida: las retenciones persistidas, la idempotencia y la conciliación resuelven fallos entre sistemas.

## Reserva

`ReservationTransactions.reserve` abre una transacción corta. Bloquea primero al turista para serializar claves repetidas y luego la instancia para serializar cupos. Una clave reutilizada con cuerpo distinto produce conflicto. Suma personas de reservas comprometidas, valida fecha y cantidad, copia precio y condiciones y crea reserva y pago local. La transacción termina antes de llamar al servicio externo.

`Payments.pay` es la única entrada de autorización del turista. PaymentAttemptTransactions valida y persiste una intención con método propio, referencia UUID y plazo de la reserva. Tras el commit, PaymentClient envía el token al simulador y ReservationTransactions aplica el resultado en otra transacción. Crear la reserva no contacta al simulador.

Un rechazo deja el intento en el historial y conserva la reserva pendiente para otra tarjeta dentro del mismo plazo. UNKNOWN bloquea nuevos intentos. La conciliación recupera por referencia y solo reenvía una intención explícita ya persistida. No hay llamadas HTTP dentro de bloqueos de PostgreSQL. La restricción única de aprobación por reserva y la comprobación equivalente del simulador previenen dobles pagos.

Los métodos se registran por tokenización ficticia; platform conserva únicamente token y metadatos enmascarados. No se guardan PAN ni CVV en ninguna base. La API del simulador es autenticada y no tiene vistas ni rutas de decisión. El protocolo y sus reglas están en [PAGOS.md](PAGOS.md).

## Modelo relacional

User 1 a 0..1 Venture. Venture 1 a N Activity. Activity N a N Category y N a N EquipmentType (con modalidad). Activity 1 a N ActivityInstance. User turista 1 a N Reservation. Instance 1 a N Reservation. Reservation 1 a 1 Payment (resumen), 1 a N PaymentAttempt y 1 a 0..1 Review. Turista 1 a N PaymentMethod tokenizados. Department 1 a N Locality. Los snapshots conservan las condiciones de compra.

Las migraciones son la definición exacta del esquema. El proyecto no usa borrado en cascada sobre reservas y pagos. Las instancias referenciadas se desactivan; los catálogos utilizados se desactivan. Las actividades se retiran del portal con isPublished=false.

## Seguridad

El portal usa sesiones Spring Security, BCrypt y CSRF. Los controladores y servicios comprueban rol y dueño. La API pública solo expone proyecciones. La aprobación JSON tiene una cadena HTTP Basic sin sesión; el formulario HTML usa sesión y CSRF. El webhook exige un secreto y vuelve a consultar la operación, sin confiar en el estado recibido. El simulador exige X-API-Key, no crea sesiones ni presenta formularios. El registro de métodos y PAGAR requieren sesión, dueño y CSRF en platform; se deshabilitó la caché de peticiones de login para no guardar cuerpos sensibles en sesión.

## Tiempo y pruebas

Los servicios usan Clock para sus decisiones. Los filtros diarios y mensuales calculan límites en America/Montevideo; SQL usa intervalos [inicio, fin). Las pruebas rápidas ejecutan migraciones y páginas completas en H2 modo PostgreSQL; una segunda suite reutiliza los escenarios con PostgreSQL real mediante Testcontainers.

## Diferencias explicitadas respecto de la especificación

Java 21 es el runtime Docker, pero el código compila para Java 17 para facilitar su uso. Se eligió JDBC en lugar de JPA; ambas opciones cumplen MVC y PostgreSQL. Los manuales y el modelo de datos están en Markdown. No se incluye un PDF descargable de constancia: se ofrece la página imprimible, que el navegador permite guardar como PDF. El clima opcional no está implementado. No se aporta un catálogo nacional completo de localidades ni se declara una medición de SLA sin ejecutarla.

## Persistencia y concurrencia de cancelaciones

Flyway V3 agrega la decisión administrativa sin modificar migraciones aplicadas: un motivo de denegación en una solicitud PENDING distingue una denegación de una solicitud en revisión; la API y las pantallas la presentan como DENIED. V4 agrega políticas, snapshots, la marca irreversible de instancia cancelada y una solicitud de cancelación por reserva. El simulador agrega V2 para persistir el porcentaje y el reembolso acumulado.

CancellationTransactions toma el mismo orden de bloqueos que las reservas: instancia y luego reserva. La creación de solicitudes por cancelación de instancia es atómica. Payments procesa HTTP después del commit y concilia solicitudes pendientes cada 30 segundos. PaymentStore serializa decisión de pago y cancelación por operación; los reintentos devuelven el mismo resultado. Una devolución parcial pendiente puede elevarse al 100 % por cancelación del prestador; reintentos atrasados nunca reducen la devolución acumulada. El importe remoto se valida contra referencia, moneda, total y porcentaje antes de liberar cupos. Los callbacks posteriores no revierten la cancelación finalizada.

V5 elimina la antigua columna allow_reapply sin modificar la migración V3 aplicada ni borrar motivos de denegación. El endpoint anterior de nueva solicitud queda denegado; ni la interfaz administrativa ni su API pueden habilitarlo. RegistrationChecks comparte las validaciones de alta entre controlador y servicio; los errores de campo se devuelven junto al control correspondiente y JavaScript los anticipa sin reemplazar la validación del servidor.

## Paneles privados

WorkspaceSummary concentra consultas de lectura para el turista y el prestador, siempre limitadas al usuario autenticado. Usa Clock para los límites de tiempo. Los conteos abarcan la cuenta completa y no dependen de la página visible del historial; los listados destacados se limitan a una próxima reserva y cuatro fechas de agenda. Las cancelaciones solicitadas se excluyen de salidas/reseñas disponibles y participantes esperados.

Las plantillas privadas comparten navegación e iconos SVG locales. workspace.css solo se carga en esos paneles y no modifica los formularios de registro ni el catálogo. workspace.js filtra la bandeja administrativa en el navegador; sin JavaScript todas las solicitudes siguen disponibles. Los formularios existentes conservan sus rutas y protección CSRF. No se agregaron dependencias de producción ni migraciones para este rediseño.

V6 mantiene payment como resumen histórico y agrega payment_attempt, payment_method y la asociación de eventos a intentos. Los pagos previos se copian como intentos legados, sin reinterpretarlos como nuevas autorizaciones. V3 del simulador conserva las operaciones y agrega reglas/tokenización sin columnas PAN ni CVV. Una cancelación por referencia puede sellar una operación antes de recibir la autorización: esa marca impide cargos tardíos y conserva la conciliación de reembolsos.
