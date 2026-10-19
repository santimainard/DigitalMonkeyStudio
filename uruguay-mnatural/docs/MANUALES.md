# Manuales de uso

## Visitante y turista

1. Entrá al catálogo; combiná departamento, localidad, categorías, día, rango de precio y texto. Las tarjetas indican precio por persona.
2. Abrí una actividad para consultar descripción, requisitos, equipamiento, opiniones y próximas instancias.
3. Registrate como turista con nombre, apellido, documento, correo y contraseña. Iniciá sesión.
4. Elegí fecha y cantidad. Revisá inicio, fin y total. Al confirmar se comprueban cupos y plazo en el servidor.
5. En el detalle de la reserva, abrí el simulador y elegí aprobar, rechazar o cancelar. No ingreses una tarjeta real.
6. Volvé a tu reserva. Si aún está pendiente, usá Actualizar estado; también se consulta automáticamente cada 30 segundos desde el servidor.
7. Una reserva confirmada tiene código y constancia imprimible. Si figura Comprobando pago, no crees otra para volver a pagar.
8. Después de la hora de fin, con pago aprobado, el detalle habilita una reseña de 1 a 5 y comentario opcional. Solo se admite una por reserva.

## Prestador

1. Elegí Soy prestador en el registro. Completá responsable, tipo de personería y emprendimiento; una persona jurídica debe incluir razón social y RUT.
2. En Mi espacio podés editar contacto y ubicación, crear actividades y cargar fechas mientras esperás aprobación.
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

## Demostración de fallos

En el checkout, seleccioná Callback duplicado o Sin callback inmediato. El segundo caso se resuelve por conciliación. El botón Reenviar callback duplicado comprueba que no se descuentan cupos otra vez.

Para indisponibilidad o respuesta de creación perdida, enviá `POST http://localhost:8081/simulation/mode` con `X-API-Key` tomada de .env, Content-Type application/json y cuerpo `{"mode":"UNAVAILABLE"}` o `{"mode":"LOST_RESPONSE"}`. Luego creá una reserva. Restablecé `{"mode":"NORMAL"}` y actualizá el estado. `TIMEOUT` también demora la respuesta de creación 7 segundos, mayor que el timeout de 5 segundos de la app. El simulador conserva la operación creada; la conciliación busca por referencia antes de crear otra.

La hora de fin puede demostrarse creando una actividad de duración 1 minuto con inicio cercano y anticipación cero; reservá antes del inicio, pagá y esperá el fin. No hace falta modificar estados manualmente en PostgreSQL.
