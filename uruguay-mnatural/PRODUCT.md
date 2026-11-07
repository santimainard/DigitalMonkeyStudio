# Uruguay Mnatural

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Visitantes sin sesión, turistas registrados, prestadores turísticos y administradores. La interfaz debe atender a los cuatro grupos por igual.

## Product Purpose

Reunir actividades turísticas de Uruguay para que los visitantes puedan encontrarlas y los turistas puedan reservarlas. Los prestadores gestionan su emprendimiento, actividades y fechas. Los administradores revisan emprendimientos y mantienen catálogos comunes. La facilidad para encontrar y reservar actividades guía la mejora del producto.

## Operating Context

El visitante explora la oferta y ve el detalle sin iniciar sesión. Para reservar, el turista inicia sesión, elige fecha y cantidad de participantes, y completa el pago mediante un simulador externo. El prestador administra actividades y sus fechas después de la verificación del emprendimiento. El administrador aprueba emprendimientos y gestiona categorías y equipamiento.

## Capabilities and Constraints

- Aplicación web en Java, Spring Boot, Spring MVC y Thymeleaf, con PostgreSQL y un simulador de pagos separado.
- El portal público y el detalle son accesibles para visitantes sin sesión y turistas. Prestadores y administradores usan sus espacios propios.
- Las fechas de una actividad pueden tener precios distintos. El precio aplicado queda guardado en cada reserva.
- Los roles y la propiedad de los recursos se verifican en el servidor.
- El contenido de la interfaz está en español. La consigna académica exige manuales por rol y documentación de la API.
- Los plazos y estados de pago mencionados en el acta del cliente difieren de la especificación actual y requieren una decisión del equipo antes de redefinir ese flujo.

## Brand Commitments

El producto se llama Uruguay Mnatural. Digital Monkey Studio es el equipo desarrollador.

## Evidence on Hand

- `docs/REQUISITOS.md` registra casos de uso, reglas y decisiones de diseño.
- `platform/src/main/resources/templates/` contiene las pantallas actuales de los cuatro grupos.
- El acta de reunión del cliente está en la raíz del repositorio principal.

## Product Principles

1. Los cuatro grupos deben reconocer la misma aplicación y entender su próximo paso.
2. Encontrar una actividad y reservarla debe requerir decisiones claras y pocos desvíos.
3. Cada rol debe ver únicamente las acciones que puede realizar.
4. Precio, disponibilidad y estado de una reserva deben presentarse con claridad.
