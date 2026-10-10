# Product Requirements Document — TunAI API (Backend)

| Campo | Valor |
|---|---|
| Producto | TunAI — visualización y personalización virtual de vehículos en 3D |
| Componente | API intermediaria de generación 3D (repositorio **backend**) |
| Versión del documento | 1.6 |
| Fecha | 2026-10-09 (v1.0: 2026-10-03) |
| Autor | Anderson |
| Estado | Aprobado para MVP (con puntos **PENDIENTE DE DEFINIR / VALIDAR**) |
| Documento relacionado | `PRD-TunAI-Frontend.md` (aplicación de escritorio JavaFX) |
| Stack | Java 21 LTS · Spring Boot 4.1.x (versión fijada en `pom.xml`) · Maven (wrapper `mvnw`) |
| Despliegue | Render (Web Service con Docker) |
| Base de datos | **Ninguna** (decisión del producto) |
| Paquete raíz | `com.tunai.api` |
| Fecha de presentación | 9 de noviembre de 2026 |
| Modelo de IA | Stable Fast 3D (Stability AI), consumido por API — referencia: https://github.com/Stability-AI/stable-fast-3d.git |
| Presupuesto | **0 USD**: solo los 25 créditos gratuitos de una cuenta nueva de Stability (= **2 generaciones reales**) |
| Contexto de uso | Proyecto educativo; presentado únicamente por el autor |

### Historial de cambios
| Versión | Fecha | Cambios |
|---|---|---|
| 1.0 | 2026-10-03 | Versión inicial |
| 1.1 | 2026-10-09 | Stack alineado con el repositorio: Maven y Spring Boot 4.1.x (antes Gradle y 3.x). Presupuesto limitado a los créditos gratuitos (2 generaciones): límites por IP y global en 2; nuevo adaptador `fake` para desarrollo (RF-BE-021); sin llamadas reales durante el desarrollo (RN-15); se elimina el prototipo de la Fase 0; manejo de la clave (§18); riesgos R-BE-10 a R-BE-12; verificación de vehículo en el cliente (RN-16) |
| 1.2 | 2026-10-09 | Revisión de huecos: IP real desde `X-Forwarded-For` (RF-BE-022); multipart solo en memoria (RF-BE-018); 415 vs 400 y 413 de Spring (§15.4); headers de cuota en 429 por IP y `Retry-After` definidos (RF-BE-015); tamaño máximo del GLB; `traceId` (RF-BE-023); liberación de la reserva ante cualquier fallo (§13.5); cuota en modo `fake` (§13.8); logs JSON nativos de Spring Boot; checklist de ensayo y demo (Anexo E); referencias obsoletas a "créditos comprados" y al prototipo corregidas |
| 1.3 | 2026-10-09 | Catálogo con `BAD_REQUEST`, `NOT_FOUND` y `METHOD_NOT_ALLOWED`; `type` como URN `urn:tunai:problem:*`; base URL de Render definida (P-BE-02); `POST /api/v1/generations` disponible con el proveedor `fake` antes de la cuota |
| 1.4 | 2026-10-09 | Cuota: la IP real sale de `CF-Connecting-IP` (Render va detrás de Cloudflare; el último valor de `X-Forwarded-For` es un proxy) (RF-BE-022, supuesto 4); los headers de cuota no exponen el límite global (RF-BE-015); la cuota en memoria usa un único lock (§13.6); variables `TUNAI_CLIENT_IP_HEADER` y `TUNAI_TRUSTED_PROXIES` |
| 1.5 | 2026-10-09 | Adaptador de Stability: contrato verificado (endpoint vigente, campo `vertex_count`), traducción defensiva de errores (403 solo es moderación si el cuerpo lo dice), cliente HTTP/1.1, lectura acotada del GLB; variables de timeouts y `TUNAI_VERTEX_COUNT` |
| 1.6 | 2026-10-09 | Métricas `tunai.generations` y `tunai.provider.latency` publicadas en el log; Checkstyle en el build |

**Convenciones:** igual que el PRD del frontend.
- Prioridad MoSCoW (Must, Should, Could, Won't).
- Identificadores `RF-BE-xxx`, `RNF-BE-xxx`, `RN-xx` (compartidas), `US-BE-xx`.
- **PENDIENTE DE DEFINIR** / **PENDIENTE DE VALIDAR** para lo no decidido o no verificado.

---

## 1. Información general del producto

### 1.1 Nombre
**TunAI API**

### 1.2 Descripción
Servicio HTTP **sin estado persistente** que recibe una foto de vehículo desde la app TunAI, aplica validaciones y la cuota de uso, la envía a la API **Stable Fast 3D** de Stability AI y devuelve al cliente el modelo 3D (GLB).

### 1.3 Propósito
1. **Proteger la clave de API** de Stability AI: nunca debe estar en el cliente de escritorio.
2. **Controlar el gasto:** cuota por instalación, límites globales y concurrencia.
3. **Aislar al cliente del proveedor:** contrato propio y estable, de modo que cambiar de proveedor no obliga a cambiar la app.

### 1.4 Contexto
- El producto completo se describe en `PRD-TunAI-Frontend.md`.
- La generación se hace en la nube porque los modelos locales requieren GPU de 10–29 GB.
- Se eligió SF3D por su textura UV, que necesita el recoloreado local en JavaFX, por quitar la iluminación de la foto y por su costo de ~0,10 USD por generación.

### 1.5 Problema que resuelve
Sin backend, la clave de Stability tendría que ir dentro de la app y cualquiera podría extraerla y gastar los créditos. Tampoco habría forma de limitar el uso.

### 1.6 Alcance (MVP)
- Endpoint de generación (proxy validado hacia SF3D).
- Endpoint de consulta de cuota.
- Health check.
- Cuota de **2 generaciones por día por instalación**, con límites adicionales por IP y globales.
- Manejo uniforme de errores (RFC 9457).
- Despliegue en Render con Docker.

### 1.7 Fuera de alcance
- Base de datos y persistencia de imágenes o modelos (el garaje vive en el cliente).
- Cuentas y autenticación de usuarios.
- Quitar el fondo (lo hace la API de SF3D).
- Otros proveedores de IA (el diseño lo permite; no se implementan).
- Panel de administración.

---

## 2. Investigación y contexto

### 2.1 Proveedor elegido: Stable Fast 3D (API Stability AI)
- **Endpoint:** `POST https://api.stability.ai/v2beta/3d/stable-fast-3d` (multipart).
- **Autenticación:** `Authorization: Bearer <API_KEY>`.
- **Vigencia (2026-10-09):** la documentación oficial sigue enlazando `/v2beta/3d/stable-fast-3d` con precio de 10 créditos. La especificación OpenAPI y el catálogo de errores no son públicos.
- **Parámetros conocidos:**
  - `image` (obligatorio);
  - `texture_resolution` (`512` | `1024` | `2048`);
  - `foreground_ratio` (0,1–1,0; por defecto 0,85);
  - `remesh` (`none` | `quad` | `triangle`);
  - `vertex_count` (opcional; `-1` = valor del proveedor). Verificado en el notebook oficial `Stable_3D_API.ipynb`.
- **Respuesta:** GLB (glTF binario) con malla UV-desenvuelta, textura base sin iluminación y parámetros de material.
- **Fondo:** la API lo elimina automáticamente. **PENDIENTE DE VALIDAR** con fotos reales de carros.
- **Costo:** 10 créditos por generación (1 crédito = 0,01 USD → ~0,10 USD). Las cuentas nuevas reciben 25 créditos gratuitos.
- **Decisión de presupuesto (v1.1):** no se compran créditos. Los 25 gratuitos alcanzan para **2 generaciones**: una de ensayo antes de la presentación y una en vivo durante la demo (RN-15).
- **Licencia del modelo:** Stability AI Community License; gratuita para uso comercial con ingresos < 1 M USD/año. Términos específicos de las salidas de la API: **PENDIENTE DE VALIDAR**.
- **Repositorio de referencia del modelo** (comportamiento, parámetros, licencia): **https://github.com/Stability-AI/stable-fast-3d.git**. El backend **no** ejecuta este código; consume la API alojada. El repositorio se usa como documentación técnica.

### 2.2 Alternativas descartadas
| Alternativa | Motivo |
|---|---|
| TripoSR (fal.ai) | Colores por vértice, incompatibles con `TriangleMesh` de JavaFX |
| Ejecutar SF3D, TRELLIS o Hunyuan en el servidor | Requiere GPU; Render gratuito no tiene GPU y ofrece 512 MB de RAM |
| Quitar el fondo en el backend (rembg/ONNX) | Innecesario (lo hace SF3D) y no cabe en 512 MB de RAM |

### 2.3 Restricciones de la plataforma (Render, plan gratuito)
- El servicio se **apaga tras 15 minutos sin tráfico**; reactivarlo tarda alrededor de 1 minuto.
- **Sistema de archivos efímero:** los cambios locales se pierden al apagarse o redesplegar.
- 512 MB de RAM y CPU compartida.
- 750 horas de instancia al mes por workspace.
- La Postgres gratuita expira a los 30 días. No aplica: no hay base de datos.

### 2.4 Fuentes
- Stability AI — Pricing: https://platform.stability.ai/pricing
- Stability AI — API Reference: https://platform.stability.ai/docs/api-reference
- SF3D — paper: https://arxiv.org/abs/2408.00653
- SF3D — repositorio: https://github.com/Stability-AI/stable-fast-3d.git
- Render — Deploy for Free: https://render.com/docs/free
- Render — X-Forwarded-For no se limpia, solo se agrega: https://feedback.render.com/features/p/send-the-correct-xforwardedfor
- Render — proxies delante de la app: https://community.render.com/t/what-number-of-proxies-sit-in-front-of-an-express-app-deployed-on-render/35981
- RFC 9457 (Problem Details): https://www.rfc-editor.org/rfc/rfc9457

---

## 3. Objetivos del componente

| ID | Objetivo | Verificación |
|---|---|---|
| OBJ-BE-01 | Generar modelos 3D a partir de fotos válidas | CA-BE-01 |
| OBJ-BE-02 | Nunca exponer la clave del proveedor | CA-BE-05 |
| OBJ-BE-03 | Mantener el gasto dentro del presupuesto (máximo 2 generaciones reales, con créditos gratuitos) | CA-BE-03, CA-BE-04, CA-BE-11 |
| OBJ-BE-04 | Errores predecibles y documentados para el cliente | CA-BE-06 |
| OBJ-BE-05 | Código mantenible y extensible sin deuda técnica | CA-BE-08 |

---

## 4. Usuarios objetivo
- **Consumidor directo:** la aplicación TunAI Desktop (sistema cliente).
- **Usuario final indirecto:** propietario de vehículo (ver PRD frontend, §4).
- **Operador:** el desarrollador, que despliega y configura variables de entorno en Render.

---

## 5. Roles y permisos

| Actor | Puede | No puede |
|---|---|---|
| Cliente TunAI (identificado por `X-Installation-Id`) | Consultar su cuota y generar dentro de la cuota | Superar la cuota; acceder a datos de otras instalaciones |
| Operador | Configurar límites, clave y zona horaria por variables de entorno | — |
| Público anónimo | `GET /actuator/health` | Cualquier otra operación sin `X-Installation-Id` válido |

> El `X-Installation-Id` **no es un mecanismo de autenticación**: un atacante puede inventar IDs. Por eso existen límites por IP y globales (RN-10, RN-11), que acotan el gasto máximo.

---

## 6. User Stories

**US-BE-01** — Como app TunAI, quiero enviar una foto y recibir un GLB, para mostrar el vehículo en 3D.
- **CA:** respuesta `200` con `Content-Type: model/gltf-binary`, cuerpo GLB y headers de cuota.

**US-BE-02** — Como app TunAI, quiero consultar la cuota sin generar, para informar al usuario antes de enviar.
- **CA:** `GET /api/v1/quota` devuelve `limit`, `remaining` y `resetsAt`, sin consumir cuota.

**US-BE-03** — Como operador, quiero que una generación fallida no descuente la cuota, para no castigar al usuario por fallos ajenos.
- **CA:** ante un error del proveedor o un tiempo agotado, la cuota reservada se libera.

**US-BE-04** — Como operador, quiero limitar el gasto total aunque se inventen IDs de instalación, para proteger el presupuesto.
- **CA:** límites por IP y globales diarios configurables; al superarlos se responde `429` o `503`.

**US-BE-05** — Como app TunAI, quiero errores con un código estable, para mostrar mensajes adecuados.
- **CA:** todas las respuestas de error usan `application/problem+json` con el campo `code` (sección 15.4).

**US-BE-06** — Como operador, quiero un endpoint de salud, para que Render y la app verifiquen el servicio.
- **CA:** `GET /actuator/health` → `200 {"status":"UP"}`.

---

## 7. Funcionalidades

### F-BE-01 — Generar modelo 3D (Must)
- **Usuario:** cliente TunAI.
- **Flujo principal:**
  1. Recibir el multipart.
  2. Validar el header `X-Installation-Id` (UUID).
  3. Validar la imagen (firma de bytes, tamaño, dimensiones).
  4. Adquirir un permiso de concurrencia.
  5. **Reservar** cuota de forma atómica (instalación + IP + global).
  6. Llamar a SF3D.
  7. Validar que la respuesta sea un GLB (magic `glTF`).
  8. **Confirmar** la reserva.
  9. Responder `200` con el GLB y los headers de cuota.
  10. Liberar el permiso de concurrencia.
- **Flujos alternativos:**
  - **A1 — Header ausente o inválido** → `400 INVALID_INSTALLATION_ID`.
  - **A2 — Imagen inválida** → `400 INVALID_IMAGE` / `413 IMAGE_TOO_LARGE` / `415 UNSUPPORTED_MEDIA_TYPE`. No se reserva cuota.
  - **A3 — Sin permiso de concurrencia** → `503 SERVICE_BUSY` + `Retry-After`.
  - **A4 — Cuota de instalación o IP agotada** → `429 QUOTA_EXCEEDED` + `Retry-After` + headers de cuota.
  - **A5 — Límite global diario agotado** → `503 SERVICE_UNAVAILABLE` (sin revelar detalles del presupuesto).
  - **A6 — El proveedor rechaza la imagen** (4xx del proveedor, por ejemplo, moderación) → **liberar** la reserva → `422 PROVIDER_REJECTED`.
  - **A7 — Error 5xx del proveedor o respuesta no-GLB** → **liberar** → `502 PROVIDER_ERROR`.
  - **A8 — Tiempo agotado con el proveedor** → **liberar** → `504 PROVIDER_TIMEOUT`.
  - **A9 — Error de autenticación con el proveedor** (clave inválida o sin créditos) → **liberar** → `503 SERVICE_UNAVAILABLE` y log de nivel ERROR para el operador.
- **Reglas de negocio:** RN-02, RN-03, RN-04, RN-10, RN-11, RN-12, RN-13.
- **Datos:** imagen (bytes), ID de instalación, IP del cliente (RF-BE-022), versión del cliente.
- **Criterios de aceptación:** US-BE-01, US-BE-03, US-BE-04, US-BE-05.

### F-BE-02 — Consultar cuota (Must)
- **Flujo principal:** validar el header → leer el estado de la cuota → `200` con JSON.
- **Flujos alternativos:** header inválido → `400`.
- **Criterios de aceptación:** US-BE-02.

### F-BE-03 — Salud del servicio (Must)
- Spring Boot Actuator, con **solo** `health` expuesto y sin detalles internos.
- Se configura como *Health Check Path* en Render.

---

## 8. Flujos

### Flujo 1 — Generación (secuencia)
```
Cliente            GenerationController     GenerateModelService       QuotaStore        StabilityAdapter      Stability AI
  │ POST multipart ──▶│                          │                         │                    │                     │
  │                   │─ validar header/imagen ─▶│                         │                    │                     │
  │                   │                          │─ tryReserve(inst, ip) ─▶│                    │                     │
  │                   │                          │◀──── Reservation ───────│                    │                     │
  │                   │                          │─ generate(image) ──────────────────────────▶│─ POST sf3d ────────▶│
  │                   │                          │◀────────────── GeneratedModel ──────────────│◀──── GLB ───────────│
  │                   │                          │─ commit(reservation) ──▶│                    │                     │
  │ ◀── 200 GLB + X-Quota-* ─────────────────────│                         │                    │                     │
  (en error del proveedor: release(reservation) y ProblemDetail)
```

### Flujos 2–4 del producto
Ocurren en el cliente (ver PRD frontend §8); el backend no participa.

---

## 9. Requisitos funcionales

| ID | Requisito | Prioridad |
|---|---|---|
| RF-BE-001 | Exponer `POST /api/v1/generations` (multipart, campo `image`) que devuelva GLB con `Content-Type: model/gltf-binary` | Must |
| RF-BE-002 | Exponer `GET /api/v1/quota` con `limit`, `remaining` y `resetsAt` | Must |
| RF-BE-003 | Exponer `GET /actuator/health` público, sin detalles | Must |
| RF-BE-004 | Exigir `X-Installation-Id` con formato UUID en todos los endpoints `/api/**` | Must |
| RF-BE-005 | Validar la imagen por firma de bytes (JPEG `FF D8 FF`, PNG `89 50 4E 47`), sin confiar en el `Content-Type` ni en la extensión | Must |
| RF-BE-006 | Rechazar imágenes > 10 MB (configurable) y con lado menor < 512 px; leer las dimensiones sin decodificar la imagen completa | Must |
| RF-BE-007 | Aplicar la cuota por instalación: 2 generaciones exitosas por día (configurable) | Must |
| RF-BE-008 | Aplicar la cuota por IP: 2 generaciones exitosas por día (configurable) | Must |
| RF-BE-009 | Aplicar el límite global: 2 generaciones exitosas por día (configurable) | Must |
| RF-BE-010 | Reservar la cuota de forma atómica antes de llamar al proveedor, confirmarla si hay éxito y liberarla si hay fallo | Must |
| RF-BE-011 | Limitar las llamadas simultáneas al proveedor (configurable; propuesta 2) y responder `503 SERVICE_BUSY` si no hay cupo | Must |
| RF-BE-012 | Llamar a SF3D con `texture_resolution`, `foreground_ratio` y `remesh` configurables (por defecto `1024`, `0.85`, `none`) | Must |
| RF-BE-013 | Verificar que la respuesta del proveedor empiece con el magic de GLB (`glTF`) y no exceda un tamaño máximo configurable (por defecto 20 MB, `tunai.generation.max-model-bytes`). Aplica también al adaptador `fake` | Must |
| RF-BE-014 | Traducir los errores del proveedor a los códigos propios (sección 15.4) | Must |
| RF-BE-015 | Devolver los headers `X-Quota-Limit`, `X-Quota-Remaining` y `X-Quota-Reset` en respuestas de generación y cuota, y `Retry-After` en `429` y `503`. Los headers `X-Quota-*` reflejan **el límite más restrictivo entre instalación e IP**, para que un `429` nunca llegue con `remaining > 0`; el límite global **no** se expone, para no revelar el presupuesto (A5). `X-Quota-Reset` va en ISO-8601 UTC. `Retry-After` va en segundos: hasta las 00:00 de la zona configurada en `429` y en `503` por límite global; 5 s en `503 SERVICE_BUSY` | Must |
| RF-BE-016 | Responder todos los errores como `application/problem+json` (RFC 9457) con las propiedades `code` y `traceId` | Must |
| RF-BE-017 | Calcular el reinicio diario de la cuota a las 00:00 de la zona horaria configurada (por defecto `America/Bogota`) | Must |
| RF-BE-018 | No escribir imágenes ni modelos en disco; procesarlos solo en memoria. Por defecto Tomcat guarda los archivos multipart en disco temporal: se configura `spring.servlet.multipart.file-size-threshold` igual a `max-file-size` para que queden en memoria (cuenta contra RNF-BE-003) | Must |
| RF-BE-019 | Documentar la API con OpenAPI (springdoc), habilitado solo en el perfil `dev` | Should |
| RF-BE-020 | Antes de generar, consultar el saldo de créditos de Stability y responder `503` si es insuficiente (endpoint de saldo **PENDIENTE DE VALIDAR**) | Could |
| RF-BE-021 | Proveer un adaptador `fake` de `ModelGeneratorPort` que devuelva un GLB de ejemplo sin llamar a ningún proveedor. El adaptador se elige con `tunai.generator.provider` (`fake` \| `stability`); el valor por defecto es `fake`, de modo que una llamada real solo ocurre si se configura `stability` de forma explícita | Must |
| RF-BE-022 | Obtener la IP real del cliente, en este orden: (1) el header de confianza del proxy de borde (`CF-Connecting-IP`: Render va detrás de Cloudflare, que lo sobrescribe); (2) el valor de `X-Forwarded-For` que agregó el primero de N proxies de confianza contando desde la derecha (N configurable, por defecto 1), **nunca** el primero de la lista, que lo controla el cliente; (3) la IP de la conexión | Must |
| RF-BE-023 | Generar un `traceId` por petición (filtro web, guardado en el MDC de los logs), devolverlo en el header `X-Trace-Id` y en el campo `traceId` de los errores | Must |

---

## 10. Requisitos no funcionales

| ID | Categoría | Requisito |
|---|---|---|
| RNF-BE-001 | Rendimiento | Sobrecarga propia del backend (sin contar el proveedor) < 300 ms por generación |
| RNF-BE-002 | Tiempos | Timeout de conexión con el proveedor 10 s; timeout de lectura 60 s (**PENDIENTE DE VALIDAR** con mediciones reales) |
| RNF-BE-003 | Memoria | Funcionar en 512 MB de RAM: `-XX:MaxRAMPercentage=75`, concurrencia ≤ 2, límites de tamaño de entrada y salida |
| RNF-BE-004 | Disponibilidad | Aceptada la del plan gratuito de Render (apagado por inactividad); el cliente lo compensa con el calentamiento |
| RNF-BE-005 | Escalabilidad | Una instancia en el MVP. La cuota en memoria **no** es compartible entre instancias; escalar requiere un adaptador `QuotaStorePort` persistente (documentado, ver §13.6) |
| RNF-BE-006 | Seguridad | Clave del proveedor solo en variable de entorno; nunca en logs, respuestas ni repositorio |
| RNF-BE-007 | Seguridad | HTTPS (provisto por Render); límites de multipart en Spring: `max-file-size` = 10 MB y `max-request-size` = 11 MB (margen para los encabezados del multipart) |
| RNF-BE-008 | Seguridad | Actuator: solo `health`; sin `env`, `beans` ni `heapdump` |
| RNF-BE-009 | Privacidad | No registrar en logs imágenes, ni bytes de modelo, ni la IP completa (se enmascara); el ID de instalación se registra como hash |
| RNF-BE-010 | Manejo de errores | Todas las excepciones pasan por un único `@RestControllerAdvice`; ningún stack trace llega al cliente |
| RNF-BE-011 | Recuperación | Un fallo del proveedor libera la cuota y los permisos (`try/finally`); un reinicio del servicio deja el sistema en estado limpio |
| RNF-BE-012 | Observabilidad | Logs estructurados (JSON) con `traceId`, usando el soporte nativo de Spring Boot (`logging.structured.format.console=ecs`, perfil `prod`); en local, logs legibles. Métricas Micrometer internas `tunai.generations{provider, result}` y `tunai.provider.latency{provider, result}` (`result`: success, rejected, timeout, unavailable, error), medidas por un decorador del puerto `ModelGeneratorPort`. Como Actuator solo expone `health`, se publican en el log cada `TUNAI_METRICS_LOG_INTERVAL` (5 min) y solo si hubo actividad; las métricas de la JVM se descartan para ahorrar memoria |
| RNF-BE-013 | Mantenibilidad | Arquitectura hexagonal verificada con ArchUnit en CI |
| RNF-BE-014 | Calidad | Spotless (palantir-java-format), Checkstyle (`config/checkstyle/checkstyle.xml`: errores probables, nombres y prácticas prohibidas; el formato lo cubre Spotless) y JaCoCo; cobertura mínima de **70 %** de líneas en los paquetes `domain` y `application`; el CI falla si baja de ese umbral o hay violaciones |
| RNF-BE-015 | Configuración | Toda la configuración con `@ConfigurationProperties` validada (`@Validated`) al arrancar: si el proveedor es `stability` y falta la clave, o si un límite es inválido, la app no inicia |
| RNF-BE-016 | Compatibilidad | API versionada en la ruta (`/api/v1`); cambios incompatibles solo en `/api/v2` |

---

## 11. Reglas de negocio

| ID | Regla |
|---|---|
| RN-02 | Máximo **2 generaciones exitosas por día por instalación**; el día se reinicia a las 00:00 en `America/Bogota` (configurable) |
| RN-03 | Las generaciones fallidas (rechazo, error, tiempo agotado del proveedor) **no consumen cuota** |
| RN-04 | Solo JPEG y PNG, ≤ 10 MB, lado menor ≥ 512 px |
| RN-10 | Máximo **2 generaciones exitosas por día por IP**, para acotar IDs de instalación inventados |
| RN-11 | Máximo **2 generaciones exitosas por día en total**, igual al saldo de créditos gratuitos (2 generaciones) |
| RN-12 | No se almacenan imágenes ni modelos en el servidor |
| RN-13 | Nunca se reintenta automáticamente una llamada al proveedor (cada éxito tiene costo) |
| RN-14 | **Limitación aceptada:** la cuota vive en memoria; un reinicio o apagado del servicio la reinicia. El tope real de gasto es el **saldo de créditos** (25 gratuitos, sin compras): al agotarse, Stability rechaza la llamada y el backend responde `503 SERVICE_UNAVAILABLE` (A9) |
| RN-15 | Durante el desarrollo **no se hacen llamadas reales** al proveedor: se usa el adaptador `fake` (RF-BE-021) y WireMock. Solo hay 2 generaciones reales: **1 ensayo** unos días antes de la presentación y **1 en vivo** durante la demo |
| RN-16 | Solo se envían al proveedor fotos que contengan un **vehículo**. SF3D no lo verifica: modela el objeto principal de cualquier foto y cobra la generación. La verificación la hace el **cliente** con un clasificador de imágenes local antes de enviar (ver `HANDOFF-Frontend-UI.md` §5.3.1). **Limitación aceptada:** el backend no la repite, por la RAM de Render (512 MB) y porque el único cliente es la app oficial; un cliente alterado podría saltarla, y el gasto queda acotado por la cuota (RN-02, RN-10, RN-11) |

---

## 12. Modelo de datos

No hay persistencia. Modelo de dominio en memoria:

| Tipo | Clase | Atributos | Notas |
|---|---|---|---|
| Objeto de valor | `InstallationId` | `UUID value` | Se valida al construirse |
| Objeto de valor | `ClientIp` | `String value` | Normalizada |
| Objeto de valor | `VehicleImage` | `byte[] data`, `ImageFormat format`, `int width`, `int height` | Inmutable; validada al construirse (RN-04) |
| Objeto de valor | `GenerationOptions` | `textureResolution`, `foregroundRatio`, `remesh` | Desde la configuración |
| Objeto de valor | `GeneratedModel` | `byte[] glb` | Se valida el magic `glTF` |
| Objeto de valor | `QuotaStatus` | `int limit`, `int remaining`, `Instant resetsAt` | |
| Entidad efímera | `QuotaReservation` | `id`, `installationId`, `clientIp`, `day` | Resultado de `tryReserve` |
| Estado interno | `DailyCounter` (adaptador) | `LocalDate day`, `int committed`, `int reserved` | Por clave (instalación / IP / global) |

**Relaciones:** `QuotaReservation` → 1 `InstallationId`, 1 `ClientIp`. Sin otras relaciones.

**Entidades descartadas:** Usuario (no hay cuentas), Vehículo, Configuración y Biblioteca (viven en el cliente).

---

## 13. Arquitectura propuesta

### 13.1 Estilo
**Arquitectura hexagonal (puertos y adaptadores), alineada con Clean Architecture**. Spring se usa solo en los adaptadores y en la configuración; el dominio y la aplicación son Java puro.

```
adapter.in.web  ──▶  application (casos de uso)  ──▶  domain
                         │ usa puertos de salida
adapter.out.stability ◀──┘ (implementa ModelGeneratorPort)
adapter.out.quota     ◀──── (implementa QuotaStorePort)
config (Spring)  → ensambla beans
```

### 13.2 Estructura de paquetes
```
com.tunai.api
├── domain
│   ├── model        (InstallationId, ClientIp, VehicleImage, ImageFormat, GenerationOptions,
│   │                 GeneratedModel, QuotaStatus, QuotaReservation)
│   └── exception    (InvalidImageException, QuotaExceededException, GlobalLimitReachedException,
│                     ProviderRejectedException, ProviderFailureException, ProviderTimeoutException,
│                     ProviderUnavailableException, ServiceBusyException)
├── application
│   ├── port.in      (GenerateModelUseCase, GetQuotaUseCase)
│   ├── port.out     (ModelGeneratorPort, QuotaStorePort, ConcurrencyLimiterPort)
│   └── service      (GenerateModelService, GetQuotaService)
├── adapter
│   ├── in.web       (GenerationController, QuotaController, ApiExceptionHandler,
│   │                 ClientContextResolver, ImageRequestMapper, QuotaHeaders)
│   └── out
│       ├── stability (StabilityFast3dAdapter, StabilityErrorTranslator; StabilityProperties vive en config)
│       ├── fake      (FakeModelGenerator: GLB de ejemplo para desarrollo, RF-BE-021)
│       ├── quota     (InMemoryQuotaStore, DailyCounter)
│       ├── imageio   (ImageIoDimensionsReader)
│       └── concurrency (SemaphoreConcurrencyLimiter)
└── config           (TunaiProperties, BeanConfiguration, ClockConfiguration, WebConfiguration)
```

### 13.3 Patrones de diseño aplicados
| Patrón | Dónde | Por qué |
|---|---|---|
| Ports & Adapters | `application.port.*` + `adapter.*` | Cambiar proveedor de IA o almacén de cuota sin tocar los casos de uso |
| Adapter | `StabilityFast3dAdapter` traduce el contrato de Stability al dominio | Aísla la API externa |
| Strategy | `ModelGeneratorPort` (`stability` para la demo, `fake` para desarrollo; otros proveedores mañana) | Abierto/cerrado; se elige por configuración |
| Reservation (reserve → commit/release) | `QuotaStorePort` | Evita condiciones de carrera y garantiza RN-03 |
| Value Object | Paquete `domain.model` | Invariantes en el constructor; inmutabilidad |
| Translator / Anti-Corruption Layer | `StabilityErrorTranslator` | Los códigos del proveedor no se filtran al cliente |
| Global Exception Handler | `ApiExceptionHandler` (`@RestControllerAdvice` + `ProblemDetail`) | Errores uniformes (RFC 9457) |
| Options / Configuration Object | `@ConfigurationProperties` validadas | Sin valores fijos en el código |
| Dependency Injection | Inyección por constructor (Spring) | Testeable y explícita |

### 13.4 Principios SOLID
- **S:** el controlador solo traduce HTTP ↔ caso de uso; el servicio orquesta; el adaptador habla con Stability.
- **O:** un nuevo proveedor o almacén de cuota es una nueva implementación de su puerto.
- **L:** `InMemoryQuotaStore` y un futuro almacén persistente cumplen el mismo contrato (tests de contrato compartidos).
- **I:** puertos separados para generación, cuota y concurrencia.
- **D:** `GenerateModelService` depende de interfaces; Spring inyecta las implementaciones desde `config`.

### 13.5 Lógica del caso de uso (pseudocódigo)
```java
public GeneratedModelResult generate(GenerateCommand cmd) {
    VehicleImage image = cmd.image();                    // ya validada (objeto de valor)
    try (var permit = concurrency.acquire()) {           // ServiceBusyException si no hay cupo
        QuotaReservation r = quota.tryReserve(cmd.installationId(), cmd.clientIp()); // 429/503
        boolean committed = false;
        try {
            GeneratedModel model = generator.generate(image, options);
            QuotaStatus status = quota.commit(r);
            committed = true;
            return new GeneratedModelResult(model, status);
        } finally {
            if (!committed) {
                quota.release(r);                        // RN-03: ante CUALQUIER fallo, no solo del proveedor
            }
        }
    }
}
```

### 13.6 Cuota en memoria (decisión explícita, sin deuda oculta)
- `InMemoryQuotaStore` protege todas sus operaciones con **un único lock**: la reserva debe ser atómica sobre tres contadores (instalación, IP y global) y la concurrencia es ≤ 2, así que un lock global es más simple y seguro que operaciones atómicas por clave. Al cambiar el día se descartan todos los contadores y las reservas pendientes; una reserva del día anterior que se confirme después no cuenta en el día nuevo.
- **Consecuencia aceptada (RN-14):** un reinicio la vacía. Como Render apaga el servicio tras 15 minutos sin tráfico, el límite global diario **no** es un tope confiable: el tope real es el saldo de créditos (25 gratuitos).
- **Camino de evolución:** implementar `QuotaStorePort` con un almacén persistente (por ejemplo, Redis o Postgres) **sin cambios** en dominio, aplicación ni web. Los tests de contrato del puerto garantizan el mismo comportamiento.

### 13.7 Comunicación con Stability
- `RestClient` de Spring (síncrono; una petición por hilo es suficiente con concurrencia ≤ 2), con los timeouts de RNF-BE-002.
- Cuerpo multipart con `image`, `texture_resolution`, `foreground_ratio` y `remesh`.
- **Cliente:** HTTP/1.1 explícito (una subida por petición no gana nada con HTTP/2 y el *upgrade* h2c falla con algunos servidores). El multipart se arma a mano: en Spring 7, `MultipartBodyBuilder` depende de Reactive Streams, que la app no incluye.
- **Respuesta:** se lee como máximo `max-model-bytes + 1` para detectar un GLB demasiado grande sin cargarlo entero.
- **Mapeo de errores del proveedor** (`StabilityErrorTranslator`). Stability no publica el catálogo de errores de este endpoint, así que la traducción es defensiva (P-BE-04):
  - 400, 413 y 422 → `ProviderRejectedException` (422);
  - 403 → `ProviderRejectedException` si el cuerpo menciona *moderation*; si no, `ProviderUnavailableException` (503);
  - 401, 402 y 404 → `ProviderUnavailableException` (503, log ERROR: clave, créditos o endpoint);
  - 429 → `ProviderUnavailableException` con `Retry-After` si viene en segundos;
  - 5xx y cualquier otro → `ProviderFailureException` (502);
  - timeout de conexión o de respuesta → `ProviderTimeoutException` (504); sin conexión → `ProviderFailureException`.
- El cuerpo de error se resume (máx. 500 caracteres) solo en el log interno; nunca llega al cliente.

### 13.8 Cuota en modo `fake`
- La cuota se aplica igual con el proveedor `fake`, para poder probar `429` y los headers.
- Como los límites por defecto (2) frenarían el desarrollo, en local y en las pruebas de integración con el frontend se suben por variables de entorno (por ejemplo, `TUNAI_QUOTA_*=50`).
- Al arrancar, el backend registra en el log el proveedor activo y los límites efectivos. Si el proveedor es `stability` y el límite global es mayor que 2, lo registra como advertencia (WARN).
- Volver los límites a 2 forma parte del checklist del ensayo (Anexo E).

---

## 14. IA y generación 3D

| Aspecto | Definición | Estado |
|---|---|---|
| Entrada | 1 imagen JPEG o PNG, ≤ 10 MB, lado menor ≥ 512 px (SF3D trabaja internamente a 512×512) | Confirmado |
| Preprocesamiento | Ninguno en el backend; SF3D quita el fondo y recorta | Confirmado (**PENDIENTE DE VALIDAR** con fotos reales) |
| ¿Es un vehículo? | SF3D no lo verifica. Lo verifica el cliente con un clasificador local (MobileNet / ImageNet vía ONNX Runtime) antes de enviar (RN-16) | Decidido; umbral **PENDIENTE DE AJUSTAR** con fotos de prueba |
| Modelo / servicio | Stable Fast 3D vía API de Stability AI — https://github.com/Stability-AI/stable-fast-3d.git | Confirmado |
| Parámetros | `texture_resolution=1024`, `foreground_ratio=0.85`, `remesh=none` (configurables) | Valores fijos para el ensayo y la demo; con solo 2 generaciones no hay margen para ajustarlos |
| Salida | GLB: malla UV-desenvuelta, textura base sin iluminación, parámetros de material | Confirmado |
| Validación | Magic `glTF`, tamaño máximo (20 MB); la validación estructural completa la hace el cliente | Confirmado |
| Errores | §7 A6–A9 y §15.4 | Confirmado |
| Limitaciones | Partes ocultas inventadas, sin escala, bajo número de polígonos; dependiente de la calidad de la foto | Documentado |
| Costo | 10 créditos (~0,10 USD) por generación exitosa; presupuesto: 25 créditos gratuitos = 2 generaciones (RN-15) | Confirmado |
| ¿Se cobran los fallos? | **PENDIENTE DE VALIDAR** con Stability | Pendiente |
| Dependencias externas | Disponibilidad, precios y términos de Stability AI | Riesgo R-BE-01 |
| Retención de imágenes por Stability | **PENDIENTE DE VALIDAR** (términos y política de privacidad del proveedor) | Pendiente |

---

## 15. API y comunicación entre componentes

### 15.1 Convenciones
- **Base URL:** `https://tunai-api-qd5x.onrender.com`.
- **Headers obligatorios en `/api/**`:** `X-Installation-Id: <UUID>`. **Opcional:** `X-Client-Version: <semver>` (solo registro).
- **Errores:** `application/problem+json`:
```json
{
  "type": "urn:tunai:problem:quota-exceeded",
  "title": "Cuota diaria agotada",
  "status": 429,
  "detail": "Se alcanzó el máximo de 2 generaciones para hoy.",
  "code": "QUOTA_EXCEEDED",
  "traceId": "8f2c1e..."
}
```
El campo `type` es una URN estable (`urn:tunai:problem:<code-en-minúsculas-con-guiones>`), para no depender de un dominio propio. Cada respuesta incluye también el header `X-Trace-Id` (RF-BE-023).

### 15.2 Endpoints

**`GET /actuator/health`**
- Respuesta `200`: `{"status":"UP"}`.

**`GET /api/v1/quota`**
- Respuesta `200`:
```json
{ "limit": 2, "remaining": 1, "resetsAt": "2026-10-04T05:00:00Z" }
```
- Headers: `X-Quota-Limit`, `X-Quota-Remaining`, `X-Quota-Reset`.
- Errores: `400 INVALID_INSTALLATION_ID`.

**`POST /api/v1/generations`**
- Request: `multipart/form-data`, campo `image` (JPEG o PNG).
- Respuesta `200`: `Content-Type: model/gltf-binary`, cuerpo GLB, headers `X-Quota-*`.
- Ejemplo:
```bash
curl -X POST https://tunai-api-qd5x.onrender.com/api/v1/generations \
  -H "X-Installation-Id: 3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80" \
  -F "image=@carro.jpg" \
  -o modelo.glb -D -
```

### 15.3 Ejemplo de cliente (referencia para el frontend)
Ver `PRD-TunAI-Frontend.md` §15 y `HANDOFF-Frontend-UI.md`. Cliente: `java.net.http.HttpClient` con timeout total de 120 s, sin reintentos automáticos en `POST`.

### 15.4 Catálogo de errores

| HTTP | `code` | Cuándo | ¿Consume cuota? |
|---|---|---|---|
| 400 | `BAD_REQUEST` | Petición mal formada que no encaja en otro código (genérico) | No |
| 400 | `INVALID_INSTALLATION_ID` | Header ausente o no UUID | No |
| 400 | `INVALID_IMAGE` | Los bytes no corresponden a ninguna imagen reconocible, dimensiones insuficientes o imagen ilegible | No |
| 413 | `IMAGE_TOO_LARGE` | > 10 MB. Incluye el `MaxUploadSizeExceededException` que lanza Spring antes de llegar al controlador | No |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Imagen reconocible pero de un formato no soportado (WEBP, HEIC, GIF, BMP), detectado por su firma de bytes | No |
| 422 | `PROVIDER_REJECTED` | El proveedor rechazó la imagen | No |
| 429 | `QUOTA_EXCEEDED` | Cuota por instalación o por IP agotada (+ `Retry-After`) | — |
| 502 | `PROVIDER_ERROR` | Error 5xx del proveedor o respuesta no-GLB | No |
| 503 | `SERVICE_BUSY` | Sin cupo de concurrencia (+ `Retry-After`) | No |
| 503 | `SERVICE_UNAVAILABLE` | Límite global, proveedor sin créditos o con clave inválida | No |
| 504 | `PROVIDER_TIMEOUT` | Tiempo agotado con el proveedor | No |
| 404 | `NOT_FOUND` | La ruta no existe | No |
| 405 | `METHOD_NOT_ALLOWED` | Método HTTP no permitido en la ruta (+ header `Allow`) | No |
| 500 | `INTERNAL_ERROR` | Error inesperado (sin detalles internos) | No |

> El cliente tiene además códigos propios que el backend nunca envía (`NOT_A_VEHICLE`, `CLIENT_TIMEOUT`, etc.); ver `HANDOFF-Frontend-UI.md` §7.

### 15.5 Versionado del contrato
- Ambos repositorios referencian esta sección como contrato oficial.
- Un cambio incompatible exige `/api/v2` y una versión mayor de ambos PRD.

---

## 16. Biblioteca de vehículos
No aplica al backend: el garaje es local (PRD frontend §16). El backend no guarda vehículos, imágenes ni modelos (RN-12).

## 17. Personalización y modificaciones
No aplica al backend: pintura, rines, comparación y exportación ocurren en el cliente (PRD frontend §17).

---

## 18. Seguridad y privacidad

| Tema | Definición |
|---|---|
| Autenticación | Ninguna de usuario (no hay cuentas). El `X-Installation-Id` es solo un identificador de cuota, no una credencial |
| Autorización | Cada instalación solo consulta su propia cuota |
| Abuso | Límites por IP y globales, concurrencia, tamaño de entrada y salida (RN-10, RN-11, RF-BE-011) |
| Secretos | `STABILITY_API_KEY` solo como variable de entorno; `.env` y secretos excluidos del repositorio (`.gitignore`); escaneo de secretos en CI (GitHub secret scanning). La clave la configura el autor **a mano**: en local, desde "Editar las variables de entorno de esta cuenta" de Windows (no por consola, para que no quede en el historial); en Render, desde *Environment* del dashboard. **Nunca** se pega en chats, asistentes de IA, issues ni archivos del proyecto |
| Protección de imágenes | Solo en memoria durante la petición; no se escriben a disco ni se registran |
| Comunicación externa | HTTPS hacia Stability; HTTPS (Render) hacia el cliente |
| Cabeceras | `X-Content-Type-Options: nosniff`; CORS **deshabilitado** (no hay clientes de navegador) |
| Dependencias | Revisión de vulnerabilidades con Dependabot en GitHub |
| Eliminación de información | No hay datos persistidos. La cuota en memoria expira al cambiar el día |
| Transferencia internacional | Las fotos viajan a Stability AI (fuera de Colombia): base legal y texto de consentimiento según Ley 1581 de 2012, art. 26 — **PENDIENTE DE VALIDAR** |

---

## 19. Propiedad intelectual

| Elemento | Consideración | Estado |
|---|---|---|
| Imágenes del usuario | Se reenvían al proveedor solo para generar el modelo; no se conservan | Confirmado |
| Modelos generados | Se devuelven al usuario; el backend no los conserva ni los redistribuye | Confirmado |
| Marcas y diseños de vehículos | El backend no maneja catálogos ni marcas | Confirmado |
| Uso de SF3D y sus salidas | Stability AI Community License y términos de la API | **PENDIENTE DE VALIDAR** |
| Código de terceros | Solo librerías con licencias permisivas (§23) | Confirmado |

---

## 20. MVP

### MVP
Generación (proxy SF3D) · cuota 2/día por instalación · límites por IP y global · concurrencia · errores RFC 9457 · health · despliegue Render con Docker · CI con tests y ArchUnit.

### Versión posterior
Almacén de cuota persistente (`QuotaStorePort`) · consulta del saldo del proveedor (RF-BE-020) · soporte multivista (otro adaptador de `ModelGeneratorPort`).

### Futuras posibilidades
Proveedores alternativos (Meshy, Hunyuan3D por API) seleccionables por configuración · autenticación si se crean cuentas.

---

## 21. Métricas de éxito

Valores objetivo **PENDIENTE DE DEFINIR**, salvo los indicados en los RNF.

| Métrica | Fuente |
|---|---|
| Generaciones por resultado (éxito, rechazo, error, tiempo agotado, no disponible) | Contador `tunai.generations{provider, result}` en el log |
| Latencia del proveedor (media y máximo por intervalo) | `tunai.provider.latency{provider, result}` en el log |
| Tasa de `429` y `503` | Logs |
| Generaciones reales vs presupuesto (2) | Logs + panel de Stability |
| Tiempo de arranque en frío observado | Logs de inicio de Render |

---

## 22. Riesgos y mitigaciones

| ID | Riesgo | Probabilidad | Impacto | Mitigación |
|---|---|---|---|---|
| R-BE-01 | Cambios de precio, términos o disponibilidad de la API de Stability | Media | Alto | Puerto `ModelGeneratorPort` para cambiar de proveedor; contrato propio estable |
| R-BE-02 | Gasto fuera de control por IDs de instalación inventados | Media | Alto | Límites por IP (con la IP real, RF-BE-022) y globales; concurrencia; el saldo gratuito (25 créditos) es el tope absoluto |
| R-BE-03 | Cuota reiniciada por apagado del servicio (en memoria): el límite global deja de ser confiable | Alta | Bajo | El saldo gratuito acota el gasto; el proveedor queda en `fake` salvo en el ensayo y la demo (Anexo E); documentado (RN-14) |
| R-BE-04 | Falta de memoria con 512 MB | Media | Medio | Concurrencia ≤ 2, límites de tamaño, `MaxRAMPercentage`, sin decodificar imágenes completas |
| R-BE-05 | Arranque en frío durante la demo | Alta | Medio | El cliente calienta el servicio; abrir la app minutos antes de presentar |
| R-BE-06 | Códigos de error de Stability distintos a los supuestos | **PENDIENTE DE VALIDAR** | Medio | `StabilityErrorTranslator` aislado + tests con WireMock; ajustar tras el ensayo |
| R-BE-07 | Fuga de la clave de API | Baja | Alto | Solo variable de entorno, escaneo de secretos, nunca en logs |
| R-BE-08 | Cumplimiento de la Ley 1581 (transferencia internacional) | **PENDIENTE DE VALIDAR** | Medio | Consentimiento en el cliente; no conservar datos; validación legal |
| R-BE-09 | Tiempo de respuesta del proveedor mayor de lo esperado | Baja | Medio | Timeouts configurables; liberar la cuota ante tiempo agotado |
| R-BE-10 | Solo hay 2 generaciones reales: un fallo en el ensayo o en vivo (clave, formato, timeout, foto rechazada) deja la demo sin modelo nuevo | Media | Alto | Ensayo con 1 crédito unos días antes para detectar errores con tiempo; conservar el GLB del ensayo como respaldo para la demo; usar en vivo una foto ya probada o muy similar |
| R-BE-11 | Gasto accidental de créditos durante el desarrollo | Media | Alto | Proveedor por defecto `fake` (RF-BE-021); tests con WireMock; la clave solo se configura donde se necesita |
| R-BE-12 | Se envía una foto que no es de un vehículo, o una foto mala de un carro (cortado, de detalle, con varios carros): SF3D genera un modelo inútil y cobra la generación | Media | Alto | Clasificador de vehículos en el cliente (RN-16); consejos de foto en la UI; confirmación antes de generar; en la demo, usar una foto ya probada |

---

## 23. Dependencias

| Tipo | Dependencia | Uso | Licencia |
|---|---|---|---|
| Runtime | Java 21 LTS (Eclipse Temurin en Docker) | Ejecución | GPL v2 + CE |
| Framework | Spring Boot 4.1.x: `spring-boot-starter-webmvc`, `-validation`, `-actuator` | API, validación, health | Apache 2.0 |
| Documentación | springdoc-openapi (solo perfil `dev`) | OpenAPI / Swagger UI | Apache 2.0 |
| Observabilidad | Micrometer (incluido), Logback con logs estructurados nativos de Spring Boot (sin librerías extra) | Métricas y logs | Apache 2.0 / EPL-LGPL |
| Pruebas | JUnit 5, Mockito, AssertJ, Spring Boot Test (MockMvc), WireMock, ArchUnit | Tests | EPL / MIT / Apache |
| Calidad | Spotless, Checkstyle, JaCoCo | Estilo y cobertura | Apache / LGPL / EPL |
| Build | Maven (wrapper `mvnw`) | Compilación | Apache 2.0 |
| API externa | Stability AI — Stable Fast 3D (`/v2beta/3d/stable-fast-3d`) | Generación 3D | Términos de Stability AI |
| Referencia del modelo | https://github.com/Stability-AI/stable-fast-3d.git | Documentación técnica y licencia del modelo | Stability AI Community License |
| Infraestructura | Render Web Service (Docker, plan gratuito) | Hosting HTTPS | — |
| CI/CD | GitHub Actions (build, test, ArchUnit) + auto-deploy de Render desde `main` | Integración y despliegue | — |
| Base de datos | Ninguna | — | — |

---

## 24. Supuestos
1. La API de SF3D quita el fondo de fotos de carros sin preprocesamiento.
2. Las generaciones fallidas no se cobran (**PENDIENTE DE VALIDAR**).
3. La latencia de SF3D vía API es de pocos segundos, muy por debajo de 60 s.
4. Render va detrás de Cloudflare, que entrega la IP real del cliente en `CF-Connecting-IP`. **Validado el 2026-10-09** en el servicio desplegado: un `X-Forwarded-For` falsificado se ignora y se registra la IP real; Cloudflare rechaza con 403 (error 1000) cualquier petición que traiga su propio `CF-Connecting-IP`, así que el cliente no puede falsificarlo.
5. 512 MB de RAM bastan con concurrencia 2 y entradas de hasta 10 MB.
6. La cuenta nueva de Stability sigue recibiendo 25 créditos gratuitos y el precio de SF3D sigue en 10 créditos (**PENDIENTE DE VALIDAR** al crear la cuenta).

---

## 25. Preguntas pendientes

| ID | Pregunta | Tipo |
|---|---|---|
| P-BE-01 | ¿Límite por IP = 4 y límite global = 6 por día? | Resuelta: sí en v1.0; en v1.1 ambos bajan a **2** por el presupuesto |
| P-BE-02 | Nombre definitivo del servicio en Render (base URL) | Resuelta: `https://tunai-api-qd5x.onrender.com` (servicio `tunai-api`, región Ohio, rama `main`) |
| P-BE-03 | Paquete raíz Java | Resuelta: `com.tunai.api` (backend) y `com.tunai.desktop` (frontend) |
| P-BE-04 | Códigos de error exactos de Stability, si cobra los fallos y si el endpoint `v2beta` sigue vigente | Parcial: endpoint y campos verificados (2026-10-09); catálogo de errores y cobro de fallos no publicados, se cubren con traducción defensiva (§13.7) y se observan en el ensayo |
| P-BE-05 | Endpoint de saldo de créditos de Stability (RF-BE-020) | PENDIENTE DE VALIDAR |
| P-BE-06 | Retención de imágenes por Stability y base legal (Ley 1581) | PENDIENTE DE VALIDAR |
| P-BE-07 | Cobertura mínima de pruebas | Resuelta: 70 % en dominio y aplicación |
| P-BE-08 | ¿Proveedor gratuito alternativo (Google, Hugging Face, ejecución local)? | Resuelta: no. Google no ofrece una API de imagen a 3D; se usan los créditos gratuitos de Stability (2 generaciones) |
| P-BE-09 | ¿Se mantiene Lombok? El dominio y la aplicación deben ser Java puro y los `record` cubren los objetos de valor | PENDIENTE DE DEFINIR (por ahora no se usa en el código) |

---

## 26. Roadmap

| Fase | Contenido | Criterio de salida |
|---|---|---|
| ~~0. Prototipo~~ | **Eliminada en v1.1**: gastaría los créditos de la demo. Los supuestos 1 y 3 se evalúan en el ensayo (Fase 6) | — |
| 1. Fundaciones | Proyecto, paquetes hexagonales, ArchUnit, CI, Dockerfile, despliegue en Render con health; adaptador `fake` (RF-BE-021); `traceId` (RF-BE-023) | `GET /actuator/health` en Render |
| 2. Validación y errores | Objetos de valor, `ApiExceptionHandler`, catálogo de errores. Además, `POST /api/v1/generations` queda disponible con el proveedor `fake` (validación completa, **sin** cuota ni headers `X-Quota-*` hasta la Fase 3), para que el frontend integre antes | Tests de cada código de error |
| 3. Cuota y límites | `InMemoryQuotaStore` (reservar, confirmar, liberar), límites por IP y globales, concurrencia | Tests de concurrencia y de cambio de día |
| 4. Adaptador Stability | `StabilityFast3dAdapter` + traductor de errores, probado **solo con WireMock** (RN-15) | Tests de 200, 4xx, 5xx, tiempo agotado y no-GLB en verde |
| 5. Integración con el frontend | Pruebas con el cliente JavaFX contra el adaptador `fake` | Flujo 1 completo |
| 6. Ensayo y demo | **Ensayo** (1 crédito) unos días antes: proveedor `stability` en Render, foto real, medir latencia, guardar el GLB como respaldo. **Demo** (1 crédito) en vivo | Ensayo exitoso; criterios §27 |

---

## 27. Criterios de aceptación generales

| ID | Criterio |
|---|---|
| CA-BE-01 | Una foto JPEG o PNG válida produce `200` con un GLB que el cliente carga correctamente |
| CA-BE-02 | Imágenes inválidas producen `400`, `413` o `415` sin llamar al proveedor ni consumir cuota |
| CA-BE-03 | La tercera generación exitosa del día de una instalación produce `429 QUOTA_EXCEEDED` con `Retry-After`; la cuota se reinicia a las 00:00 de America/Bogota |
| CA-BE-04 | Los límites por IP, globales y de concurrencia se aplican según la configuración |
| CA-BE-05 | La clave de Stability no aparece en el repositorio, ni en los logs, ni en las respuestas |
| CA-BE-06 | Todos los errores son `application/problem+json` con `code` del catálogo §15.4 y sin stack traces |
| CA-BE-07 | Los fallos del proveedor (rechazo, 5xx, tiempo agotado) liberan la cuota |
| CA-BE-08 | Los tests unitarios, de integración (WireMock) y de arquitectura (ArchUnit) pasan en CI |
| CA-BE-09 | El servicio funciona en Render gratuito (512 MB) y responde `GET /actuator/health` tras el arranque en frío |
| CA-BE-10 | No se escriben imágenes ni modelos en disco (verificado por revisión y test) |
| CA-BE-11 | Con la configuración por defecto (`fake`) ninguna petición llega a Stability; el saldo de créditos antes del ensayo sigue en 25 |

---

## Anexo A — Configuración (variables de entorno)

| Variable | Propiedad | Valor por defecto | Obligatoria |
|---|---|---|---|
| `TUNAI_GENERATOR_PROVIDER` | `tunai.generator.provider` | `fake` | No (en Render, `stability` solo para el ensayo y la demo) |
| `STABILITY_API_KEY` | `tunai.stability.api-key` | — | Solo si el proveedor es `stability` |
| `TUNAI_STABILITY_BASE_URL` | `tunai.stability.base-url` | `https://api.stability.ai` | No |
| `TUNAI_TEXTURE_RESOLUTION` | `tunai.stability.texture-resolution` | `1024` | No |
| `TUNAI_FOREGROUND_RATIO` | `tunai.stability.foreground-ratio` | `0.85` | No |
| `TUNAI_REMESH` | `tunai.stability.remesh` | `none` | No |
| `TUNAI_VERTEX_COUNT` | `tunai.stability.vertex-count` | `-1` (no se envía) | No |
| `TUNAI_STABILITY_CONNECT_TIMEOUT` | `tunai.stability.connect-timeout` | `10s` | No |
| `TUNAI_STABILITY_READ_TIMEOUT` | `tunai.stability.read-timeout` | `60s` | No |
| `TUNAI_QUOTA_PER_INSTALLATION` | `tunai.quota.per-installation` | `2` | No |
| `TUNAI_QUOTA_PER_IP` | `tunai.quota.per-ip` | `2` | No |
| `TUNAI_QUOTA_GLOBAL_DAILY` | `tunai.quota.global-daily` | `2` | No |
| `TUNAI_QUOTA_TIMEZONE` | `tunai.quota.timezone` | `America/Bogota` | No |
| `TUNAI_CLIENT_IP_HEADER` | `tunai.client-ip.trusted-header` | `CF-Connecting-IP` | No (vacío para no usarlo) |
| `TUNAI_TRUSTED_PROXIES` | `tunai.client-ip.trusted-proxies` | `1` | No |
| `TUNAI_MAX_CONCURRENT` | `tunai.generation.max-concurrent` | `2` | No |
| `TUNAI_MAX_MODEL_BYTES` | `tunai.generation.max-model-bytes` | `20971520` (20 MB) | No |
| `TUNAI_FAKE_DELAY` | `tunai.generator.fake.delay` | `2s` | No (simula la latencia del proveedor) |
| `TUNAI_MAX_IMAGE_BYTES` | `tunai.image.max-bytes` | `10485760` | No |
| `TUNAI_MIN_IMAGE_SIDE` | `tunai.image.min-side` | `512` | No |
| `PORT` | `server.port` | Lo asigna Render | Sí (en Render) |
| `JAVA_TOOL_OPTIONS` | — | `-XX:MaxRAMPercentage=75` | Recomendado |
| `SPRING_PROFILES_ACTIVE` | — | — | `prod` en Render (logs JSON) |
| `TUNAI_METRICS_LOG_INTERVAL` | `tunai.metrics.log-interval` | `5m` | No |

## Anexo B — Dockerfile de referencia
```dockerfile
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 tunai
COPY --from=build /app/target/*.jar app.jar
USER tunai
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```
- Los tests se ejecutan en CI (GitHub Actions), no dentro del build de Docker.
- Render ejecuta el contenedor con `PORT` y el health check `/actuator/health`.
- `mvnw` debe tener permiso de ejecución en Git (`git update-index --chmod=+x mvnw`) y finales de línea LF (`.gitattributes`); si no, el build falla en Linux.

## Anexo C — Estrategia de pruebas

| Nivel | Qué | Herramienta |
|---|---|---|
| Dominio | Objetos de valor (validación de imagen, ID), `QuotaStatus` | JUnit 5 + AssertJ |
| Aplicación | `GenerateModelService`: éxito, cada tipo de fallo, liberación de cuota | Mockito |
| Contrato del puerto | Suite común para cualquier `QuotaStorePort` (reservar, confirmar, liberar, cambio de día con `Clock` fijo, concurrencia) | JUnit 5 |
| Adaptador Stability | Respuestas 200, 4xx, 5xx, tiempo agotado y no-GLB | WireMock |
| Web | Controladores, headers, `ProblemDetail`, límites de multipart | MockMvc |
| Arquitectura | `domain` sin Spring; dependencias solo hacia adentro | ArchUnit |
| Extremo a extremo (manual) | 1 generación real de ensayo contra Stability antes de la demo (RN-15); el resto del flujo se prueba con el adaptador `fake` | Lista de verificación |

## Anexo D — Definición de terminado
1. Tests en verde.
2. ArchUnit en verde.
3. Spotless y Checkstyle limpios.
4. Ningún secreto en el diff.
5. Errores mapeados al catálogo.
6. Requisito trazado (RF o US) en el PR.
7. Desplegado en Render y health en `UP`.

## Anexo E — Checklist de ensayo y demo

**Ensayo (1 crédito, unos días antes del 9 de noviembre):**
1. Verificar en el panel de Stability que el saldo sea 25 créditos y que el endpoint y el precio de SF3D no hayan cambiado (P-BE-04, supuesto 6).
2. En Render → *Environment*: `STABILITY_API_KEY` configurada (a mano), `TUNAI_GENERATOR_PROVIDER=stability`, `TUNAI_QUOTA_PER_INSTALLATION=2`, `TUNAI_QUOTA_PER_IP=2`, `TUNAI_QUOTA_GLOBAL_DAILY=2`.
3. Esperar el redespliegue y confirmar en el log de arranque el proveedor `stability` y los límites en 2.
4. Desde la app instalada: despertar el servidor, elegir una foto que pase el clasificador y generar.
5. Medir la latencia, guardar el GLB como respaldo y revisar el recoloreado.
6. Volver a `TUNAI_GENERATOR_PROVIDER=fake`.

**Demo (1 crédito):**
1. Confirmar que queda saldo para 1 generación.
2. Cambiar a `stability` con los límites en 2; abrir la app unos minutos antes para despertar el servidor.
3. Usar una foto ya probada (o muy parecida a la del ensayo).
4. Tener el GLB del ensayo en el garaje como respaldo.
