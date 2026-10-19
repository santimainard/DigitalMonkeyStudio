# Requisitos de la plataforma Uruguay Mnatural

Especificación funcional y técnica para implementar la aplicación del Proyecto TIC 2026. Versión 1.0, 5 de octubre de 2026. Destinatarios: equipo de desarrollo, docente cliente e IA de asistencia a la programación.

La aplicación reunirá actividades turísticas de Uruguay, permitirá reservar y pagar mediante un servicio externo simulado y ofrecerá espacios de gestión para prestadores y administradores. Este documento transforma los CU-01 a CU-13 en comportamientos verificables y completa la API propia solicitada por la consigna. El apartado final contiene un prompt que debe utilizarse junto con la especificación completa.

## 1 Alcance y procedencia de las decisiones

**Fuentes.** Proyecto TIC 2026(1).pdf, páginas 1 a 5; CASOS DE USO (1).pdf, listado inicial y casos desarrollados CU-01 a CU-13; respuestas del equipo del 5 de octubre de 2026. La consigna prevalece sobre omisiones de los casos de uso; las respuestas del equipo precisan las reglas abiertas.

| Procedencia | Definición |
| --- | --- |
| Exigido por la consigna | Portal público; cuentas; emprendimientos; actividades; fechas y cupos; anticipación; reservas; pago externo por API; reseñas; catálogos; cinco operaciones de API propia; Java, Spring Boot, Maven, MVC, SQL, Git y pruebas. |
| Confirmado por el equipo | Thymeleaf y PostgreSQL; servicio externo de pago simulado por API; aprobación por administradores; reseña habilitada al terminar la actividad con pago aprobado; restricciones a modificar o eliminar fechas con reservas pagadas; cálculo mensual del top 3 propuesto. |
| Diseño propuesto para implementar | Moneda UYU; precio por participante; una cuenta con un rol y un emprendimiento por prestador; retención de cupos de 10 minutos; una reseña por reserva; criterios de validación, paginación y contratos descritos aquí. Son valores de diseño, no exigencias textuales del docente. |
| Ampliación opcional | Pronóstico del clima. La consigna lo recomienda y CU-04 lo incorpora, pero el equipo no confirmó servicio ni alcance. Se define una extensión desactivada por defecto, sin bloquear reservas. |

**Dentro de la primera versión:** todas las funciones obligatorias; aprobación de emprendimientos; integración HTTP con un simulador de pagos separado; gestión de fallos; documentación REST y pruebas de los principales casos de uso.

**Fuera de la primera versión:** devoluciones de pagos, cancelaciones de reservas ya pagadas, pagos presenciales, facturación fiscal, registro de datos individuales de acompañantes, control de asistencia, múltiples monedas, administración de actividades ajenas y registro público de administradores. El clima es una ampliación separada.

La especificación define el alcance de software. Los demás entregables académicos siguen siendo necesarios: acta fundacional, visión, MER, secuencias, flujos, arquitectura, mockups, clases UML, actas de reunión, manuales por rol, presentaciones y código fuente.

## 2 Actores y autorización

| Actor | Acceso permitido |
| --- | --- |
| Visitante | Buscar, filtrar, ver actividades publicadas, emprendimientos verificados, fechas, disponibilidad y reseñas; registrarse e iniciar sesión. |
| Turista | Lo anterior; reservar, pagar, consultar sus reservas y constancias, y reseñar sus reservas habilitadas. |
| Prestador | Registrar y editar su emprendimiento, crear y modificar sus actividades e instancias, definir cupos y anticipación, consultar reservas y datos del titular de sus reservas. |
| Administrador | Aprobar emprendimientos pendientes y administrar categorías y tipos de equipamiento. No crea ni edita actividades en nombre de prestadores. |
| Plataforma externa | Consumir operaciones públicas de la API; la aprobación requiere autenticación de administrador. |
| Servicio de pagos | Crear y consultar operaciones; emitir resultados autenticados y escenarios de prueba. |

**RF-01.** Todo acceso privado exige autenticación y autorización en el servidor. Ocultar un botón no sustituye comprobar rol y propiedad del recurso. Un turista solo accede a sus reservas; un prestador solo accede a su emprendimiento, sus actividades y sus reservas relacionadas. Los identificadores recibidos del cliente se validan contra el usuario autenticado.

**RF-02.** Cada cuenta tiene un rol: TOURIST, PROVIDER o ADMIN. El registro público admite TOURIST y PROVIDER. ADMIN se crea mediante una carga inicial protegida, con contraseña configurable fuera del repositorio. Las credenciales de administrador no aparecen en el código ni en documentación pública.

**RF-03.** La primera versión permite un emprendimiento por prestador. Cambiar esta cardinalidad exige actualizar modelo, permisos y pantallas. El prestador puede cargar actividades e instancias mientras su emprendimiento está pendiente; su oferta solo aparece públicamente después de la aprobación. Esta regla de publicación es una propuesta de diseño coherente con CU-02 y CU-13.

## 3 Cuentas y emprendimientos

**RF-04 / CU-01.** El registro de turista solicita nombre, apellido, correo, documento y contraseña. Son obligatorios. El correo se recorta, normaliza a minúsculas y debe ser único sin distinguir mayúsculas. El documento se conserva como texto; no se impone cédula uruguaya ni unicidad porque pueden registrarse extranjeros. La contraseña se almacena mediante hash seguro, nunca cifrado reversible ni texto plano. Propuesta: mínimo 8 caracteres y máximo 72 bytes UTF-8 si se usa BCrypt.

**RF-05 / CU-02.** El registro de prestador solicita nombre del responsable, apellido, correo, contraseña y tipo de personería PHYSICAL o LEGAL. El emprendimiento solicita nombre comercial, teléfono, correo de contacto, departamento, localidad, dirección o referencia y descripción. Para persona jurídica solicita razón social y RUT como texto obligatorio; para física, documento del responsable. La cuenta y el emprendimiento se crean en una transacción: no deben quedar altas parciales. El emprendimiento comienza PENDING.

**RF-06 / CU-03.** El inicio de sesión valida correo y contraseña y dirige al espacio del rol. Credenciales inválidas producen un mensaje genérico. El cierre de sesión invalida la sesión. La aplicación usa sesión de Spring Security y formularios Thymeleaf con protección CSRF; no necesita JWT para el portal.

**RF-07 / CU-08.** El prestador consulta y modifica teléfono, contacto, ubicación y descripción de su emprendimiento. Nombre comercial, tipo de personería, razón social, RUT y documento son datos de identidad y quedan bloqueados después de la aprobación en esta versión. Cambios de contacto no quitan la aprobación. Solo el propietario modifica los datos.

**RF-08 / CU-13.** El administrador ve la lista de pendientes, abre los datos de un emprendimiento y lo aprueba. Se guardan administrador aprobador y fecha de aprobación. Una segunda aprobación devuelve el estado actual sin duplicar el evento. Aprobar no concede facultades de administrador al prestador. Rechazo, suspensión y carga de documentación son ampliaciones: la letra no especifica documentos a adjuntar y no se exige inventarlos.

**Aceptación:** un correo repetido impide el alta; un registro inválido no crea cuenta ni emprendimiento; un prestador no puede editar un emprendimiento ajeno; un emprendimiento pendiente no expone oferta pública; solo ADMIN puede aprobarlo.

## 4 Actividades y catálogos

**RF-09 / CU-09.** El prestador registra y modifica nombre, descripción, precio por participante, duración en minutos, ubicación, condiciones, requisitos de edad opcionales, categorías y equipamiento. La actividad pertenece a su emprendimiento. El precio es positivo y se guarda con dos decimales en UYU; la duración es un entero positivo. No se contemplan actividades gratuitas en esta versión para mantener un único flujo de pago.

**RF-10.** Una actividad puede tener varias categorías y necesita al menos una activa. El equipamiento se referencia desde el catálogo y cada relación indica si lo aporta el prestador o debe llevarlo el turista. El mismo tipo no puede figurar en ambas modalidades de una actividad. Las condiciones pueden describir exigencias que el sistema no verifica automáticamente, como saber nadar.

**RF-11.** Una actividad tiene isPublished. El prestador puede guardarla sin publicar. Para publicarla exige datos válidos y emprendimiento VERIFIED. Retirarla del portal impide nuevas reservas, pero conserva consultas de reservas existentes. Una oferta publicada sin futuras instancias sigue siendo consultable y muestra que no hay próximas fechas.

**RF-12.** La ubicación de cada actividad incluye departamento, localidad y dirección o referencia del punto de encuentro; puede diferir de la dirección del emprendimiento. Si se habilita clima, se incorporan latitud y longitud opcionales. La duración y el precio usados en una reserva se congelan: cambios posteriores no alteran el importe, el horario de fin ni la constancia de una reserva existente.

**RF-13 / CU-12.** El administrador crea, consulta, modifica y da de baja categorías y tipos de equipamiento. El nombre debe ser no vacío y único sin distinguir mayúsculas. Una entidad sin referencias puede eliminarse físicamente; si está referenciada, se desactiva y mantiene el historial. Las referencias existentes siguen visibles, pero las nuevas asociaciones solo admiten elementos activos. Renombrar un catálogo no elimina asociaciones.

**RF-14.** Departamentos y localidades son datos comunes precargados con una relación válida localidad-departamento. No se exige pantalla adicional de ABM para estos datos. Los formularios rechazan combinaciones inconsistentes; los filtros y la API usan identificadores de estas entidades. En datos de demostración se declaran explícitamente las localidades cargadas; no se presentan como un catálogo nacional completo si no lo son.

**Aceptación:** precio o duración inválidos impiden guardar; una actividad ajena no puede modificarse; la ficha muestra categorías, condiciones y ambas modalidades de equipamiento; una categoría utilizada se desactiva sin romper actividades ni reservas.

## 5 Instancias y disponibilidad

**RF-15 / CU-10.** Cada instancia representa una fecha y hora concreta de una actividad. Incluye startsAt, endsAt y capacity. startsAt debe ser futuro al crear; endsAt se calcula como inicio más duración de la actividad. Dos instancias de una misma actividad no pueden tener exactamente el mismo inicio. No se impide superponer instancias diferentes: la consigna no define recursos ni guías exclusivos.

**RF-16.** capacity es nulo para cupo ilimitado, o entero positivo para cupo limitado. No se usa cero para representar ilimitado. El cupo se mide en personas, no en cantidad de reservas. Se muestra disponibilidad actual, recalculada en el servidor; verla no garantiza conseguir lugares hasta crear la reserva.

**RF-17.** El prestador modifica o elimina instancias futuras que no tienen reservas confirmadas, finalizadas ni pendientes de resolución de pago. Reservas terminales fallidas se conservan y la instancia se desactiva, en lugar de borrar registros referenciados. Con reservas pagadas se bloquean fecha, hora y duración; se permite aumentar capacidad y reducirla solo hasta el total de personas con cupos comprometidos. Nunca se reduce por debajo de ese total. Se rechazan modificaciones mientras haya pagos pendientes para evitar cambiar condiciones durante la compra.

**RF-18.** Cada actividad define bookingLeadMinutes, entero mayor o igual a cero. El último instante permitido para iniciar una reserva es deadline = startsAt - bookingLeadMinutes. Se acepta cuando now <= deadline; se rechaza cuando now > deadline. Con cero minutos, el límite es exactamente startsAt. Se usa el reloj del servidor, no el del navegador.

**RF-19.** La anticipación se valida al iniciar la reserva; completar un pago ya iniciado puede cruzar el límite e incluso el inicio, dentro de su plazo de 10 minutos. Es una propuesta de implementación para no cortar operaciones en curso. La reserva copia la anticipación aplicada y la fecha de fin vigentes. Cambiar anticipación solo afecta nuevas reservas.

Los instantes se almacenan en UTC mediante tipos con zona horaria y se presentan en America/Montevideo. Los filtros por día y mes corresponden al calendario local de Uruguay. Los intervalos usan inicio incluido y fin excluido; el reloj es inyectable para probar exactamente los límites.

**Aceptación:** cupo 8 con 6 personas comprometidas admite 2 y rechaza 3; cero minutos admite iniciar exactamente a la hora de comienzo; un minuto después se rechaza; una instancia con pago confirmado no puede eliminarse ni reprogramarse.

## 6 Portal público y búsquedas

**RF-20 / CU-04.** El portal muestra únicamente actividades publicadas de emprendimientos verificados. Permite combinar filtros por departamento, localidad, categorías, fecha local, rango de precio por participante y palabra clave. Los filtros de diferentes tipos se combinan con AND; varias categorías seleccionadas se combinan con OR. La búsqueda textual es una coincidencia parcial sin distinguir mayúsculas en nombre y descripción de actividad y nombre comercial.

**RF-21.** El filtro de fecha selecciona actividades con una instancia activa cuyo inicio cae en ese día. No duplica actividades por tener varias instancias. El precio filtrado es el precio actual de la actividad, no el total de una reserva. Rangos inválidos producen error visible. Las listas son paginadas: página inicial 0, tamaño 20 y máximo 100. Orden predeterminado: nombre ascendente e identificador ascendente para desempatar.

**RF-22.** La ficha pública muestra nombre, descripción, emprendimiento, punto de encuentro, duración, precio y moneda, categorías, condiciones, edad si aplica, equipamiento, promedio y cantidad de reseñas. Muestra futuras instancias activas, hora de inicio y fin, cupos disponibles o ilimitados y límite para reservar. Las instancias agotadas o fuera de plazo se muestran sin opción de reserva. Ninguna instancia incluye datos de titulares.

**RF-23.** Al reservar desde una ficha, un visitante es dirigido al inicio de sesión; luego puede volver a la actividad. El turista elige instancia y cantidad. La pantalla muestra precio unitario, cantidad, total, moneda y plazo de pago antes de enviarlo al servicio externo.

**RF-24.** El promedio público general considera todas las reseñas publicadas de la actividad, calculado sin redondear para ordenar y presentado con un decimal. Sin reseñas, averageRating es nulo y reviewCount es cero; la interfaz indica Sin valoraciones. No se atribuye una puntuación cero a una oferta sin reseñas.

**RF-25 / extensión de CU-04.** Si se habilita clima, se consulta a un proveedor mediante HTTP por ubicación e instancia. Debe mostrarse fecha del pronóstico, unidad y origen. Si no hay coordenadas, el día supera el horizonte del proveedor o la API falla, se muestra Pronóstico no disponible. Se usan timeout y caché configurables. El clima no cambia precios, disponibilidad ni permisos y su caída no bloquea el detalle ni la reserva. La versión base mantiene weather.enabled=false.

**Aceptación:** un visitante puede explorar sin cuenta; combinar fecha y departamento funciona; una oferta pendiente queda excluida de todas las consultas públicas; la ausencia de clima no produce un error general de la ficha.

## 7 Reservas y control de cupos

**RF-26 / CU-05.** Solo TOURIST crea reservas. La solicitud contiene instanceId, participantCount entero positivo e Idempotency-Key generado por el cliente. El servidor calcula total = unitPriceSnapshot × participantCount con aritmética decimal, copia moneda UYU y toma al titular de la sesión. No acepta precio, estado, usuario ni importe enviados por el cliente como datos confiables.

**RF-27.** En una transacción corta se bloquea la fila de la instancia, se comprueban publicación, aprobación, fecha, anticipación y cupos, y se crea la reserva PENDING_PAYMENT con una retención de 10 minutos. Las solicitudes concurrentes para una instancia usan el mismo bloqueo; así no venden los mismos lugares. Las consultas remotas no se ejecutan mientras el bloqueo SQL está retenido.

Para cupo limitado, available = capacity - suma de participantCount de reservas que comprometen lugares. Comprometen lugares PENDING_PAYMENT, PAYMENT_UNCERTAIN, CONFIRMED y COMPLETED. REJECTED, EXPIRED y PAYMENT_CANCELLED no comprometen cupos. Nunca se restan lugares de nuevo al confirmar una retención: cambia el estado, no la cantidad comprometida.

**RF-28.** La clave de idempotencia es única por turista. Repetir la misma clave y el mismo cuerpo devuelve la reserva existente; usar la misma clave con otro cuerpo devuelve 409. Se conserva aunque el intento haya fallado. Una clave nueva representa una nueva intención y puede crear otra reserva. Deshabilitar doble clic en la interfaz complementa esta garantía.

**RF-29.** Creada la reserva, se solicita la operación de pago mediante HTTP con referencia única y expiresAt. Un fallo inequívoco previo a cualquier operación permite rechazar el intento. Timeout o respuesta ambigua ponen PAYMENT_UNCERTAIN y conservan cupos hasta reconciliar. Nunca se supone que timeout significa rechazo.

**RF-30.** La confirmación exige un estado APPROVED verificado contra el servicio de pagos y coincidencia de referencia, importe y moneda. La transición es atómica e idempotente. Al confirmar se genera código único y constancia consultable con actividad, instancia, titular, cantidad, precio, total y estado. Una redirección del navegador o un mensaje de éxito del cliente no confirma nada.

**Aceptación:** con un solo lugar, dos solicitudes simultáneas de una persona no pueden quedar ambas aprobadas; repetir clave no duplica reserva ni pago; precio manipulado se ignora; un timeout no muestra Confirmada.

## 8 Estados y conciliación del pago

| Reserva | Significado y salida |
| --- | --- |
| PENDING_PAYMENT | Cupos retenidos; espera la decisión del servicio externo. Pasa a CONFIRMED, REJECTED, EXPIRED, PAYMENT_CANCELLED o PAYMENT_UNCERTAIN. |
| PAYMENT_UNCERTAIN | Se desconoce el resultado remoto. Conserva cupos; consulta periódica determina la salida. No se puede reseñar ni se presenta como pagada. |
| CONFIRMED | Pago APPROVED comprobado. Pasa a COMPLETED cuando now >= endsAtSnapshot. |
| COMPLETED | Fecha y hora de fin alcanzadas con pago aprobado. Permite reseña; no acredita asistencia física. |
| REJECTED | Pago rechazado comprobado; libera retención. Terminal. |
| EXPIRED | El servicio confirma expiración sin pago; libera retención. Terminal. |
| PAYMENT_CANCELLED | Cancelación del proceso de pago confirmada por el servicio; libera retención. Terminal. No significa devolución de una reserva pagada. |

El pago tiene estados CREATED, PENDING, APPROVED, REJECTED, CANCELLED y EXPIRED. La app puede registrar UNKNOWN localmente cuando no logra conocer el estado remoto. APPROVED, REJECTED, CANCELLED y EXPIRED son terminales y el simulador no permite cambiarlos. Una reserva completada conserva el pago APPROVED.

**RF-31.** Un callback autenticado dispara verificación remota. Cada eventId se procesa una sola vez. Se ignoran repeticiones compatibles; un evento contradictorio se registra y se consulta el estado canónico, sin retroceder una reserva ya pagada. Las transiciones terminales no se sobrescriben por mensajes atrasados.

**RF-32.** Un proceso periódico, propuesto cada 30 segundos, consulta pagos pendientes o inciertos. El simulador comparte expiresAt y garantiza que no aprueba después de esa expiración. La aplicación libera cupos solo tras consultar un estado remoto terminal sin aprobación. Si el servicio permanece caído después de expiresAt, la reserva queda incierta y retiene lugares hasta recuperar comunicación. Esta elección sacrifica disponibilidad temporal para evitar sobreventa o cobros sin lugar.

**RF-33.** Si la respuesta se pierde después de aprobar el pago, la consulta posterior recupera APPROVED y confirma la reserva existente. Si el pago se aprueba antes de expiresAt pero el callback llega tarde, se confirma igualmente: los cupos seguían comprometidos. Una operación remota nunca se crea de nuevo con otra referencia por un reintento automático.

Al alcanzarse el fin de la instancia, una tarea actualiza reservas confirmadas a COMPLETED. Consultar historial o intentar reseñar también debe resolver esta condición para no depender de que la tarea haya ejecutado. Si una aprobación tardíamente conocida corresponde a una actividad ya terminada, puede pasar directamente a COMPLETED.

## 9 Contrato del servicio externo simulado

El simulador es una aplicación o proceso separado, accesible por HTTP y configurado con payment.base-url. Debe poder ejecutarse junto con la aplicación y PostgreSQL; no basta con una clase que siempre devuelve éxito. No recibe tarjetas ni cobra dinero real. Su pantalla muestra importe y referencia y permite elegir resultados de prueba.

| Método y ruta del simulador | Contrato |
| --- | --- |
| POST /payments | Cabecera X-API-Key; Idempotency-Key igual a merchantReference. Cuerpo: merchantReference, amount decimal, currency, expiresAt, callbackUrl. Devuelve paymentId, status, checkoutUrl y expiresAt. |
| GET /payments/{paymentId} | X-API-Key. Devuelve referencia, importe, moneda, estado, approvedAt si aplica y expiresAt. Es la fuente canónica para confirmar. |
| GET /payments/by-reference/{merchantReference} | Recupera una creación cuya respuesta se perdió. 404 indica que no existe operación con esa referencia. |
| POST /payments/{paymentId}/decision | Solo interfaz de pruebas del simulador. action es APPROVE, REJECT o CANCEL. Requiere operación pendiente y ahora anterior a expiresAt. |

**RI-01.** La simulación permite aprobación, rechazo, cancelación, timeout, indisponibilidad, creación con respuesta perdida y callback duplicado o retrasado. POST /payments es idempotente: misma referencia y datos devuelven la misma operación; datos distintos producen 409. La expiración se aplica también al consultar, aunque una tarea no haya ejecutado.

**RI-02.** El simulador emite POST hacia /api/v1/payment-events con eventId, paymentId, merchantReference y status. Se autentica mediante un secreto exclusivo en X-Webhook-Token. Esta ruta no usa la sesión del turista ni CSRF, pero sí valida el secreto. La aplicación consulta GET para verificar el evento antes de confirmar. El callback no contiene datos sensibles de tarjetas.

**RI-03.** El cliente HTTP usa timeout de conexión y lectura configurables; valores propuestos de 2 y 5 segundos. Reintentos automáticos se limitan a consultas y creaciones idempotentes con la misma referencia. Claves, URL y secretos se cargan desde variables de entorno y perfiles. Se registran identificadores de correlación y fallos sin contraseñas ni secretos.

**Ejemplo de creación:**

```json
{"merchantReference":"reservation-123","amount":2400.00,"currency":"UYU","expiresAt":"2026-11-10T15:10:00Z","callbackUrl":"http://app:8080/api/v1/payment-events"}
```

**Ejemplo de respuesta:**

```json
{"paymentId":"pay-123","status":"PENDING","checkoutUrl":"http://payments:8081/checkout/pay-123","expiresAt":"2026-11-10T15:10:00Z"}
```

El regreso al portal lleva solo un identificador; la pantalla consulta el estado verificado. Nunca se interpreta un parámetro success=true como evidencia del pago.

## 10 Historial reseñas y reservas del prestador

**RF-34 / CU-06.** El turista consulta sus reservas ordenadas por creación descendente, con paginación. Cada una muestra actividad, instancia, cantidad, total, fecha de creación y estado legible. El detalle incluye pago, código y constancia cuando corresponde. Un pago pendiente informa su vencimiento; un pago incierto informa que se está comprobando y no invita a pagar nuevamente con otra reserva.

**RF-35 / CU-07.** El turista puede crear una reseña sobre una reserva propia si el pago es APPROVED y now >= endsAtSnapshot. Se exige una puntuación entera entre 1 y 5; el comentario es opcional, con máximo propuesto de 2.000 caracteres. Una restricción única sobre reservationId impide duplicados. No se permiten edición o eliminación de reseñas en esta versión.

La condición de finalización se refiere al fin de la actividad, calculado desde inicio y duración, y no a medianoche del día de reserva ni a la fecha en que se compró. No se solicita confirmación de asistencia ni lista de acompañantes. Una reserva para varias personas habilita una reseña del titular, no una por acompañante. La puntuación se integra al promedio público y al ranking mensual; el documento del turista y su contacto nunca se publican.

**RF-36 / CU-11.** El prestador consulta reservas de sus actividades con filtros por actividad, instancia y estado. Ve código, cantidad de participantes, estado de reserva y pago, nombre y apellido del titular, correo y documento registrado. El total de personas se obtiene sumando cantidades. El listado no inventa nombres ni documentos de acompañantes: la letra solicita cantidad, pero no define el alta individual de cada participante.

**RF-37.** Los listados públicos de reseñas muestran puntuación, comentario si existe, fecha y nombre de pila del autor. Orden por fecha descendente e identificador descendente. El historial y las reseñas continúan vinculados a actividades retiradas de publicación. Acceder a una constancia ajena devuelve acceso denegado y nunca expone los datos.

**Aceptación:** una reserva paga cuya actividad termina a las 17:00 no habilita reseña a las 16:59; a las 17:00 sí; una reserva impaga no habilita reseña aunque haya pasado la fecha; un segundo envío con otra clave no crea otra reseña; un prestador no consulta reservas de competidores.

## 11 API REST propia

Las rutas se versionan bajo /api/v1 y producen JSON. Los listados públicos no exponen cuentas, documentos, teléfonos privados ni datos de pago. Listar todos significa permitir recorrer todos los registros mediante páginas; la API no trunca silenciosamente el conjunto. Las consultas públicas de actividades aplican las mismas reglas de publicación que el portal. La operación de emprendimientos incluye pendientes y verificados porque la consigna solicita todos; informa su estado y no publica actividades pendientes.

| Método y ruta | Acceso y resultado |
| --- | --- |
| GET /api/v1/ventures | Público. Todos los emprendimientos registrados, paginados, con verificationStatus; filtros opcionales departmentId y localityId. |
| GET /api/v1/activities/by-day | Público. date obligatorio YYYY-MM-DD; departmentId o localityId obligatorio. Devuelve instancias activas de actividades públicas cuyo inicio cae en ese día, con actividad y ubicación. |
| GET /api/v1/activities/top-rated | Público. month obligatorio YYYY-MM y departmentId obligatorio. Devuelve hasta tres actividades conforme a RF-38. |
| PATCH /api/v1/admin/ventures/{id}/verification | ADMIN. Cuerpo {"status":"VERIFIED"}. Aprueba emprendimiento y registra responsable y momento. Implementa la operación verificar emprendimiento como aprobación. |
| GET /api/v1/activities/search | Público. q obligatorio, de 1 a 100 caracteres tras recortar. Devuelve actividades públicas que coinciden por palabra clave, paginadas. |
| GET /api/v1/activities/{id} | Público. Ficha con categorías, equipo, reseñas resumidas y próximas instancias. |
| POST /api/v1/reservations | TOURIST. instanceId, participantCount e Idempotency-Key. Crea o recupera intento conforme a RF-26 a RF-29. |
| GET /api/v1/reservations/{id} | TOURIST propietario. Estado, importe y checkoutUrl si está pendiente. |
| POST /api/v1/reservations/{id}/reviews | TOURIST propietario. rating y comment opcional; aplica RF-35. |
| POST /api/v1/payment-events | Servicio externo autenticado por secreto. Recibe evento y verifica el estado canónico. |

**RF-38.** El top 3 usa reseñas creadas dentro del mes local consultado, independientemente del mes de la actividad. Agrupa por actividad de ese departamento. Solo incluye actividades públicas con al menos una reseña en ese mes. Orden: promedio mensual descendente, cantidad de reseñas mensual descendente y activityId ascendente. Calcula el promedio sin redondear al ordenar; muestra un decimal. Puede devolver 0, 1 o 2 resultados. El promedio mensual y el promedio general son campos diferentes.

**RF-39.** by-day admite días pasados y futuros; filtra por inicio de la instancia, no por cuándo fue creada ni por si atraviesa medianoche. Si se envían departamento y localidad, ambos deben ser consistentes. Los resultados incluyen cupo y deadline y permiten distinguir una instancia agotada o pasada de una reservable.

## 12 Formatos errores y documentación de la API

**API-01.** Una respuesta paginada contiene content, page, size, totalElements y totalPages. Tamaño predeterminado 20 y máximo 100; valores inválidos producen 400. Los rankings devuelven items y month, con departmentId; nunca simulan tres elementos si faltan datos. Fechas-hora usan ISO 8601 con offset explícito; filtros date y month se interpretan en America/Montevideo.

**API-02.** DTO públicos usan nombres en inglés. VentureSummary contiene id, commercialName, description, verificationStatus, contactEmail, phone, departmentId, departmentName, localityId, localityName y locationDescription. El contacto es el comercial declarado para publicación, nunca el de la cuenta del propietario. ActivitySummary contiene id, name, description, unitPrice, currency, durationMinutes, ventureId, ventureName, ubicación, categories, averageRating y reviewCount. InstanceSummary contiene id, activityId, startsAt, endsAt, capacity, availableCapacity, bookingDeadline y bookable. capacity y availableCapacity nulos indican ilimitado.

TopRatedItem contiene activityId, name, monthlyAverage, monthlyReviewCount y departmentId. ReservationDetail contiene id, code nullable, activityId, activityNameSnapshot, startsAtSnapshot, endsAtSnapshot, participantCount, unitPriceSnapshot, totalAmount, currency, status, paymentStatus, expiresAt, checkoutUrl nullable y canReview. Los recursos privados agregan solo los datos necesarios del titular.

**API-03.** Los estados HTTP son 200 consulta o actualización; 201 creación; 204 eliminación de catálogo no referenciado; 400 validación; 401 falta de autenticación; 403 falta de permiso; 404 recurso inexistente o no público; 409 conflicto de negocio; 502/503 integración fallida cuando el recurso no puede devolverse. Un intento guardado con resultado incierto puede devolver 201 y status PAYMENT_UNCERTAIN; la interfaz debe representarlo fielmente.

**API-04.** Un error incluye code, message, fieldErrors cuando corresponda y correlationId, sin stack trace. Códigos mínimos: EMAIL_ALREADY_USED, INVALID_INPUT, NO_CAPACITY, BOOKING_DEADLINE_PASSED, VENTURE_NOT_VERIFIED, INSTANCE_HAS_COMMITTED_RESERVATIONS, IDEMPOTENCY_CONFLICT, PAYMENT_UNCERTAIN, REVIEW_NOT_ALLOWED, REVIEW_ALREADY_EXISTS y CATALOG_IN_USE. CATALOG_IN_USE informa que corresponde desactivar en vez de eliminar.

```json
{"code":"NO_CAPACITY","message":"No hay lugares suficientes para la cantidad solicitada.","fieldErrors":[],"correlationId":"req-456"}
```

**API-05.** OpenAPI documenta todas las rutas REST, parámetros, DTO, estados, ejemplos y autorización. La API pública es consumible sin cookies. Las operaciones privadas del portal y su API de reservas usan sesión y CSRF. La API /api/v1/admin/** usa una cadena de seguridad separada, sin sesión, con HTTP Basic y rol ADMIN; HTTPS es obligatorio fuera del desarrollo local. Esta API no acepta autenticación por cookies y la excepción de CSRF queda limitada a esa cadena. La aprobación desde una pantalla Thymeleaf usa una ruta MVC con sesión y CSRF, que delega en el mismo servicio. No se desactiva CSRF globalmente. El simulador usa su secreto específico y no credenciales de administrador.

Los controladores Thymeleaf y REST delegan en los mismos servicios para que una regla no cambie entre interfaz y API. Los formularios de ABM pueden usar rutas MVC privadas distintas; OpenAPI se aplica a las rutas JSON, no a páginas HTML.

## 13 Modelo de datos y validaciones

| Entidad | Campos principales y relaciones |
| --- | --- |
| User | id, firstName, lastName, email único normalizado, document, passwordHash, role, active, createdAt. Un turista tiene muchas reservas; un prestador, un emprendimiento. |
| Venture | id, ownerId único, legalType, commercialName, legalName, taxId, contactEmail, phone, departmentId, localityId, locationDescription, description, verificationStatus, verifiedBy, verifiedAt. |
| Activity | id, ventureId, name, description, unitPrice numeric(12,2), currency, durationMinutes, bookingLeadMinutes, location, minAge opcional, conditions, isPublished. |
| ActivityCategory y EquipmentType | id, name único normalizado, active. ActivityCategoryLink asocia actividades y categorías sin duplicados. ActivityEquipment asocia tipo y modalidad PROVIDED o REQUIRED sin duplicados. |
| ActivityInstance | id, activityId, startsAt y endsAt timestamptz, capacity nullable, active. Unicidad por activityId y startsAt. |
| Reservation | id, touristId, instanceId, idempotencyKey, requestHash, participantCount, status, expiresAt, snapshots de nombre, ubicación, precio, moneda, duración, inicio, fin y anticipación, totalAmount, code único nullable, createdAt. |
| Payment | id, reservationId único, merchantReference único, externalPaymentId único nullable, amount, currency, status, approvedAt, lastCheckedAt. Una operación por reserva; un nuevo intento de compra crea nueva reserva. |
| PaymentEvent | eventId único, paymentId, receivedAt, processedAt, outcome. Conserva el control de duplicados. |
| Review | id, reservationId único, rating, comment nullable, createdAt. Actividad y autor se obtienen desde la reserva; no se aceptan del cliente. |
| Department y Locality | id y name; Locality referencia Department. Venture y Activity referencian ambos de manera consistente. |

**DAT-01.** Longitudes propuestas: nombre y apellido 80, correo 254, documento 40, nombre comercial y actividad 150, razón social 200, teléfono 40, RUT 12, ubicación 300, descripción y condiciones 5.000, comentario 2.000. Rechazar texto obligatorio vacío tras recortar. minAge, si se informa, es entero entre 0 y 120. El RUT de personas jurídicas debe contener exactamente 12 dígitos ASCII, sin letras ni separadores. No se valida el dígito verificador fiscal. Para el documento se exige formato no vacío y longitud máxima. Los correos de acceso y contacto deben tener formato nombre@dominio.com y un máximo de 254 caracteres.

**DAT-02.** Aplicar validación en DTO y restricciones SQL: claves foráneas, unicidad, rating de 1 a 5, cantidades positivas, precio positivo y anticipación no negativa. Usar BigDecimal en Java; nunca float/double para dinero. No borrar en cascada reservas, pagos ni reseñas al desactivar entidades.

**DAT-03.** Índices propuestos: instancias por activityId/startsAt; reservas por instanceId/status y touristId/createdAt; actividades por ventureId; reseñas por createdAt/reservationId; emprendimientos por verificationStatus; localidades por departmentId. Las migraciones versionadas crean esquema y restricciones. El MER debe expresar estas cardinalidades y tablas de asociación.

## 14 Arquitectura requisitos no funcionales y pantallas

**RNF-01.** Implementar Java, Spring Boot, Spring MVC y Maven; Thymeleaf, HTML, CSS y JavaScript en el frontend; PostgreSQL como persistencia. Arquitectura en capas: controller, dto, service, repository, domain, integration y config. Los controladores no contienen reglas de cupo o pago. Los nombres de clases, variables, tablas y endpoints están en inglés; textos de interfaz y manuales, en español.

**RNF-02.** Propuesta de ejecución: monolito web MVC y simulador externo de pagos como servicio separado. Java 21 es una base de diseño; fijar una versión de Spring Boot compatible y registrar versiones exactas en pom.xml y README. Incluir Maven Wrapper, perfiles de configuración, migraciones y un arranque reproducible con PostgreSQL local o Docker Compose. No usar una base en memoria para la ejecución final.

**RNF-03.** Proteger sesiones, secretos y contraseñas; escapar textos de comentarios con Thymeleaf; usar consultas parametrizadas; desactivar errores internos visibles. HTTP solo para desarrollo local; HTTPS en despliegue compartido. Mantener CSRF en operaciones de sesión. Nunca devolver entidades JPA directamente si exponen passwordHash o contactos privados.

**RNF-04.** Cupos y confirmaciones deben ser consistentes tras concurrencia, reintentos y reinicio de la app. Todas las retenciones y estados se guardan en PostgreSQL; un reinicio no los pierde. El sistema externo simulado también persiste sus operaciones para poder reconciliarlas. No confirmar en memoria y persistir después.

**RNF-05.** Interfaz adaptable a móvil y escritorio; formularios con etiquetas, mensajes por campo, foco visible, navegación por teclado y estado que no dependa solo del color. Mantener filtros al paginar. Confirmar acciones de eliminación o desactivación. Una pantalla vacía comunica ausencia de datos y una consulta fallida comunica error.

**RNF-06.** Git versiona código y migraciones. README explica instalación, variables, arranque de los tres componentes, credenciales configurables de demostración, ejecución de pruebas y uso de API. Entregar manual de visitante/turista, prestador y administrador, además de OpenAPI y ejemplos de solicitudes.

**Pantallas mínimas:** catálogo y filtros; detalle con instancias y reseñas; registro de turista; registro de prestador y emprendimiento; login; reserva y resumen; checkout simulado; resultado del pago; historial y constancia; formulario de reseña; panel del prestador con emprendimiento, actividades, cronograma y reservas; panel ADMIN con pendientes, detalle para aprobar y ABM de categorías/equipamiento.

No se inventa un SLA de producción. Objetivo de verificación local propuesto: catálogo y detalle responden en menos de 2 segundos con 1.000 actividades de prueba y 10 solicitudes concurrentes, excluyendo demora del servicio externo; se documentan máquina y resultados.

## 15 Trazabilidad de casos de uso

| Caso original | Requisitos y verificación principal |
| --- | --- |
| CU-01 Registrar turista | RF-04; alta correcta y correo duplicado. |
| CU-02 Registrar prestador | RF-05 y RF-03; transacción completa y estado pendiente. |
| CU-03 Autenticación | RF-01, RF-02 y RF-06; sesión y permisos por rol. |
| CU-04 Buscar oferta | RF-20 a RF-25; filtros, detalle, fechas y clima opcional. |
| CU-05 Reservar y pagar | RF-18, RF-19 y RF-26 a RF-33; anticipación, concurrencia y pago externo. |
| CU-06 Historial | RF-34; propiedad, estados y constancia. |
| CU-07 Reseñar | RF-35 y RF-37; fin más pago, escala y unicidad. |
| CU-08 Emprendimiento | RF-07; edición permitida y propiedad. |
| CU-09 Actividades | RF-09 a RF-12 y RF-18; datos, publicación y anticipación. |
| CU-10 Cronograma | RF-15 a RF-17; fechas, cupo e impedimentos al editar. |
| CU-11 Reservas del prestador | RF-36; datos del titular y cantidad de personas. |
| CU-12 Catálogos | RF-13 y RF-14; ABM e integridad referencial. |
| CU-13 Verificación | RF-08; aprobación por ADMIN y registro del evento. |
| CU-14 adicional Listar emprendimientos | GET /ventures; todos los registrados, estado visible y paginación completa. |
| CU-15 adicional Actividades por día | RF-39; GET /activities/by-day con día y ubicación. |
| CU-16 adicional Top mensual | RF-38; GET /activities/top-rated con mes y departamento. |
| CU-17 adicional Aprobar mediante API | PATCH /admin/ventures/{id}/verification; misma regla de CU-13. |
| CU-18 adicional Buscar por palabra clave | GET /activities/search; palabra obligatoria y coincidencia parcial. |

Los CU adicionales completan las operaciones exigidas por la consigna y no reemplazan los CU originales. RF-08 y CU-17 representan la misma aprobación expuesta por dos interfaces. CU-04 conserva el pronóstico como extensión opcional y lo declara expresamente para no confundirlo con una integración ya implementada.

**Flujo integrado de referencia:** el prestador se registra y carga su oferta; ADMIN aprueba su emprendimiento; el prestador publica una actividad y fechas; el turista busca, inicia sesión, elige cantidad y reserva; la app retiene lugares y crea un pago externo; el resultado verificado confirma o libera la retención; el turista consulta la constancia; al terminar la instancia paga, crea una reseña; el prestador consulta la reserva y la API puede incluir la reseña en el ranking del mes de publicación.

## 16 Pruebas y criterios de entrega

Las pruebas unitarias cubren servicios y reglas de negocio con un reloj controlable. Las pruebas de integración verifican PostgreSQL, autorización y el contrato HTTP del simulador; no se considera probado el bloqueo de cupos solo porque pase una prueba con un repositorio falso.

| Prueba | Resultado esperado |
| --- | --- |
| T01 Alta y login | Alta válida funciona; correo duplicado y contraseña errónea se rechazan; ADMIN no se registra públicamente. |
| T02 Propiedad | Otro turista o prestador no lee ni modifica recursos ajenos. |
| T03 Aprobación | Pendiente no aparece; ADMIN aprueba; la publicación válida aparece; turista no aprueba. |
| T04 Catálogos | Duplicados se rechazan; baja referenciada conserva historial; nuevos vínculos exigen activo. |
| T05 Fecha y anticipación | Antes y exactamente en deadline se admite; después se rechaza; comprobar lead=0 y zona horaria. |
| T06 Cupos concurrentes | Dos solicitudes simultáneas con capacidad 1 generan como máximo una retención válida de una persona. |
| T07 Total y precio congelado | 2 × 1.200 = 2.400 UYU; cambiar precio luego no cambia reserva ni pago. |
| T08 Idempotencia | Repetición devuelve mismo id; cuerpo distinto con misma clave produce 409; simulador no duplica operación. |
| T09 Pago aprobado | Solo consulta remota válida confirma; callback duplicado no duplica cupos ni constancia. |
| T10 Rechazo y cancelación | Estado terminal comprobado libera retención; no genera constancia de pago. |
| T11 Timeout y respuesta perdida | Reserva incierta retiene lugares; reconciliación descubre aprobación o rechazo y resuelve una sola vez. |
| T12 Expiración y evento tardío | Servicio caído mantiene retención; expiración confirmada libera; aprobación previa al vencimiento conocida tarde confirma. |
| T13 Reinicio | Reservas y operaciones remotas persisten; proceso de conciliación continúa sin sobreventa. |
| T14 Edición de instancia | Pagada bloquea reprogramar/eliminar; reducción debajo de lugares comprometidos se rechaza. |
| T15 Reseña | Pago más fin habilita; impago o antes del fin rechaza; escala inválida y duplicado rechazan. |
| T16 API y ranking | Cinco operaciones funcionan; límites locales de mes, empate y menos de tres resultados son correctos. |
| T17 Portal y seguridad | Formularios CSRF, textos escapados, paginación, errores por campo y estados vacíos se comprueban. |

**Entrega funcional:** se puede ejecutar de cero y demostrar registro, aprobación, publicación, búsqueda, reserva, pago simulado, historial, reseña y catálogos. Las cinco operaciones externas tienen contrato y ejemplos. Las pruebas principales pasan con datos reproducibles y no requieren cambiar manualmente estados de la base para completar el flujo.

**Entrega de diseño:** MER y UML reflejan el modelo implementado; diagramas de secuencia incluyen pago exitoso, pago incierto y concurrencia; los manuales corresponden a pantallas reales. La configuración de demostración debe permitir avanzar el reloj de prueba o preparar una instancia terminada de forma documentada, sin alterar las reglas de producción.

## 17 Prompt para implementar con una IA

Copiar este prompt y adjuntar la especificación completa. No enviar solo el prompt: las reglas detalladas, el contrato remoto y los criterios de prueba son parte del encargo.

> Actuá como desarrollador Java y asistente del equipo del Proyecto TIC 2026. Implementá la plataforma Uruguay Mnatural siguiendo estrictamente el documento de requisitos adjunto. Usá Java, Spring Boot, Spring MVC, Maven, Thymeleaf y PostgreSQL. Respetá los RF, RI, DAT, API y RNF, las restricciones de roles y la matriz de pruebas. Usá nombres de código y endpoints en inglés e interfaz en español.
>
> Antes de programar, resumí entidades, cardinalidades, permisos y estados; señalá contradicciones concretas si las encontrás. Tratá los valores identificados como diseño propuesto como configuración inicial y documentalos. No agregues funciones fuera de alcance ni atribuyas al docente reglas que no exige. Clima queda desactivado por defecto.
>
> Organizá la implementación en capas controller, dto, service, repository, domain, integration y config. Entregá un monolito web MVC y un servicio simulado de pagos separado, comunicados por HTTP, con operaciones persistidas. Ambos deben ejecutarse reproduciblemente junto con PostgreSQL. La simulación debe cubrir éxito, rechazo, cancelación, indisponibilidad, respuesta perdida y eventos duplicados o tardíos.
>
> Implementá primero migraciones, modelo y autenticación; después emprendimientos, aprobación y catálogos; luego actividades e instancias; después reservas, idempotencia, retenciones y pagos; finalmente historial, reseñas, cinco operaciones de API, documentación y pruebas. Tras cada etapa, indicá archivos cambiados, comandos para ejecutar, pruebas realizadas y requisitos cubiertos. Entregá código ejecutable, no solo pseudocódigo ni métodos vacíos.
>
> Las reservas deben validar disponibilidad y anticipación en el servidor y evitar sobreventa con bloqueo transaccional sobre la instancia. No mantengas ese bloqueo durante llamadas HTTP. No confirmes por una redirección ni liberes lugares ante timeout. Verificá importe, moneda y referencia con el servicio externo; conciliá resultados desconocidos; las transiciones y los callbacks deben ser idempotentes. Preservá los snapshots de las reservas.
>
> Implementá reseñas solo para reservas propias con pago aprobado y actividad terminada según su hora de fin guardada. No agregues control de asistencia ni datos individuales de acompañantes. No implementes devoluciones ni cancelación de reservas pagadas. Bloqueá modificaciones de fecha y eliminación cuando existan reservas pagadas conforme a la especificación.
>
> Generá README, Maven Wrapper, configuración por entorno, OpenAPI con ejemplos, migraciones, datos de demostración y manuales por rol. No incluyas secretos en Git. Ejecutá pruebas unitarias de reglas y pruebas de integración de autorización, PostgreSQL, concurrencia e integración HTTP. Documentá lo que aún no esté implementado; no presentes una prueba no ejecutada como aprobada. Explicá el código de modo que el equipo pueda revisarlo y defenderlo.

## 18 Decisiones a validar con el cliente

La aplicación puede implementarse con las decisiones de diseño aquí definidas. Antes de presentar el alcance como acuerdo formal del cliente, registrar en un acta la moneda y precio por participante, la cardinalidad de cuentas y emprendimientos, la publicación posterior a aprobación, los 10 minutos de pago, la finalización de pagos después del límite de inicio, la retención durante incertidumbre y los datos visibles al prestador.

También conviene confirmar con el docente que el servicio externo simulado satisface la integración evaluada y que la operación verificar emprendimiento de la API significa aprobar, como definió el equipo. El clima requiere decidir servicio y alcance antes de activar esa extensión. El diseño visual debe recibir la aceptación del cliente solicitada por la consigna.

Estas validaciones distinguen decisiones del equipo de requisitos contractuales; no impiden construir y probar la versión definida en este documento.
