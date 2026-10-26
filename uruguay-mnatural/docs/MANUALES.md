# Manuales de uso

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


## Visitante y turista

1. Entrá al catálogo; combiná departamento, localidad, categorías, día, rango de precio y texto. Las tarjetas indican precio por persona.
2. Abrí una actividad para consultar descripción, requisitos, equipamiento, opiniones y próximas instancias.
3. Registrate como turista con nombre, apellido, documento, correo y contraseña. Repetí la contraseña en el campo de confirmación; ambas deben coincidir. Iniciá sesión.
4. Elegí fecha y cantidad. Revisá inicio, fin y total. Al confirmar se comprueban cupos y plazo en el servidor.
5. En Mis métodos de pago registrá una tarjeta ficticia del manual de pagos. En el detalle de la reserva, seleccioná un método propio y presioná PAGAR. El resultado lo determina automáticamente el servicio externo; no ingreses tarjetas reales.
6. Volvé a tu reserva. Si aún está pendiente, usá Actualizar estado; también se consulta automáticamente cada 30 segundos desde el servidor.
7. Una reserva confirmada tiene código y constancia imprimible. Si figura Comprobando pago, no crees otra para volver a pagar.
8. Después de la hora de fin, con pago aprobado, el detalle habilita una reseña de 1 a 5 y comentario opcional. Solo se admite una por reserva.

## Prestador

1. Elegí Soy prestador en el registro. Completá responsable, contraseña y su confirmación, tipo de personería y emprendimiento; una persona jurídica debe incluir razón social y RUT.
2. En Mi espacio podés editar contacto y ubicación, crear actividades y cargar fechas después de la aprobación.
3. Cada actividad requiere precio, duración, anticipación, ubicación, condiciones y categorías. Marcá qué equipamiento aportás y qué debe llevar el turista.
4. Después de la aprobación de UM, marcá Publicar y guardá. Guardar sin marcar conserva un borrador.
5. En Fechas y cupos agregá inicio local y capacidad. Cupo vacío significa ilimitado. La fecha de fin se calcula con la duración vigente.
6. Consultá reservas desde la actividad o desde el panel. Filtrá por actividad, instancia y estado. Se muestran titular, contacto, documento y cantidad; no existen fichas individuales de acompañantes.
7. Una instancia con pagos pendientes no se modifica. Una pagada no se reprograma ni elimina; la capacidad nunca puede quedar por debajo de lugares comprometidos.

## Administrador

1. Ingresá con la cuenta configurada en .env. No hay registro público de administradores.
2. Abrí un emprendimiento para ver datos del responsable y del negocio. Aprobalo si corresponde. La aprobación registra administrador y fecha.
3. Administrá categorías y tipos de equipamiento. Podés crear, renombrar, desactivar, reactivar y eliminar. Una eliminación de un elemento utilizado lo desactiva para conservar relaciones.
4. La administración no edita actividades en nombre de los prestadores.

## Probar resultados de pago

Consultá [PAGOS.md](PAGOS.md) para los números exclusivamente ficticios: terminación 4242 aprueba, 0005 rechaza y 0003 simula un método inhabilitado. Los números completos se ingresan ocultos al registrar y no reaparecen en las vistas. Un rechazo permite elegir otra tarjeta mientras la reserva siga vigente. Si el resultado está en verificación, usá Actualizar estado antes de iniciar cualquier otra compra.

Las pruebas de indisponibilidad, timeout y respuestas perdidas se ejecutan con `scripts/http-smoke.py`; el control de fallos solo se habilita en ese entorno de prueba. La hora de fin puede demostrarse con una actividad de un minuto, reservando y pagando antes del inicio y esperando su finalización para reseñar.

## Cancelar y reembolsar

**Prestador:** después de la aprobación, abrí Nueva actividad o Editar. Cargá los minutos para devolución total y el porcentaje para cancelaciones con menor anticipación. Para cancelar una fecha, entrá en Fechas y cupos y usá **Cancelar instancia y reembolsar**. No uses Eliminar: ese botón sigue impidiendo borrar fechas con reservas comprometidas. Una cancelación deja de aceptar nuevas reservas y devuelve el total a las vigentes pagadas; las devoluciones pendientes se concilian automáticamente.

**Turista:** en Mi espacio, abrí el detalle de tu reserva. Antes de confirmar, el botón informa el porcentaje que corresponde ahora. Después muestra si la cancelación está pendiente o completada, el importe devuelto y el retenido cuando hubo un pago. Actualizar cancelación reintenta una comprobación pendiente. No se puede reseñar una reserva cancelada.

**Administrador:** en Mi espacio, abrí un emprendimiento pendiente. Podés aprobarlo o escribir el motivo y usar **Denegar emprendimiento**. El prestador verá el motivo; la denegación es definitiva y no admite una nueva solicitud.

La política de reembolso se expresa en minutos; por ejemplo, 1440 significa un día. Con anticipación 1440 y porcentaje 25, una cancelación exactamente un día antes devuelve el 100 %; más tarde, pero antes de comenzar, devuelve el 25 %. Si el total es UYU 1200, son UYU 300 de devolución y UYU 900 retenidos.

## Validaciones al crear una cuenta

Los errores aparecen en rojo junto a cada campo al escribir, al salir del campo o al intentar enviar. Los datos también se verifican en el servidor. Nombre y apellido admiten letras con tildes, espacios, apóstrofes, puntos y guiones; no números. Los correos requieren @ y un dominio completo. La contraseña debe tener al menos 8 caracteres, hasta 72 bytes UTF-8 y coincidir exactamente con su repetición.

El teléfono admite entre 6 y 15 dígitos, con + inicial y separadores habituales (espacios, paréntesis y guiones), sin letras. No se impone un prefijo uruguayo porque pueden registrarse prestadores con contactos internacionales. El documento conserva su formato de texto para admitir documentos extranjeros.

RUT y razón social se muestran y exigen únicamente al seleccionar Persona jurídica; el RUT requiere 12 dígitos. Al cambiar a Persona física se ocultan y se excluyen del envío y del almacenamiento. Departamento y localidad deben seleccionarse y corresponder entre sí.

## Mi espacio de cada rol

- **Turista:** el resumen muestra las salidas confirmadas por comenzar, los pagos pendientes y las experiencias que admiten una reseña. La próxima reserva tiene un acceso directo a su detalle. El historial conserva todos los estados y su paginación. Desde Explorar Uruguay se accede al catálogo; las propuestas del pie provienen de actividades publicadas de emprendimientos verificados.
- **Prestador habilitado:** ve sus actividades publicadas, fechas futuras y participantes con reserva confirmada. La agenda muestra las próximas cuatro instancias, con sus lugares comprometidos. Puede abrir Fechas y cupos, consultar reservas y desplegar la edición de contacto. Una cancelación pendiente de conciliación se indica en el panel.
- **Administrador:** ve solicitudes pendientes, habilitadas y denegadas. Los filtros cambian la lista sin salir del panel; abrir una solicitud muestra responsable, contacto y decisión. Los catálogos se despliegan por separado para crear, renombrar, activar, desactivar o eliminar elementos.
- **Prestador pendiente o denegado:** consulta la etapa de revisión y los datos enviados. La denegación mantiene su motivo y fecha. Las funciones operativas permanecen bloqueadas y no se habilitan nuevas solicitudes.

En móviles, la navegación lateral pasa a una franja horizontal desplazable. Los accesos de cada rol conservan los mismos permisos.
