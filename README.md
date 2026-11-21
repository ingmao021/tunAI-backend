<p align="center">
  <img src="docs/assets/logo.png" alt="TunAI" width="420">
</p>

<h1 align="center">TunAI API</h1>

<p align="center">
  Intermediario HTTP que convierte la foto de un carro en un modelo 3D (GLB) con <a href="https://developers.tripo3d.ai/en/docs">Tripo API v3</a>.<br>
  Protege la clave del proveedor y controla el gasto con cuotas.
</p>

<p align="center">
  <a href="https://github.com/ingmao021/tunAI-backend/actions/workflows/ci.yml"><img src="https://github.com/ingmao021/tunAI-backend/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white" alt="Docker">
</p>

---

## Qué hace

`foto JPEG/PNG` → validaciones y cuota → Tripo → `modelo GLB`

- Sin base de datos: las fotos y los modelos solo viven en memoria durante la petición.
- Cuota por instalación (`X-Installation-Id`), por IP y global, más un tope de concurrencia.
- Errores uniformes en `application/problem+json` (RFC 9457) con un `code` estable.
- Cliente oficial: [tunAI-desktop](https://github.com/ingmao021/tunAI-desktop) (JavaFX).

## Inicio rápido

**Requisitos:** JDK 21+ y una [clave de API de Tripo](https://developers.tripo3d.ai/en/docs). Maven no hace falta (trae `mvnw`).

```bash
git clone https://github.com/ingmao021/tunAI-backend.git
cd tunAI-backend
```

**Linux / macOS**

```bash
export TRIPO_API_KEY=tsk_...
./mvnw spring-boot:run
```

**Windows (PowerShell)**

```powershell
$env:TRIPO_API_KEY = "tsk_..."
.\mvnw.cmd spring-boot:run
```

La API queda en `http://localhost:8080`. Comprueba que responde:

```bash
curl http://localhost:8080/actuator/health   # {"status":"UP"}
```

> **La app no arranca sin `TRIPO_API_KEY`.** Nunca la pongas en archivos versionados: usa la variable de entorno o `src/main/resources/application-local.yaml` (ignorado por git) con `-Dspring-boot.run.profiles=local`.

> ⚠️ **Cada generación exitosa gasta ~40 créditos reales de Tripo.** Para probar la clave y la red sin gastar nada, arranca con `TUNAI_TRIPO_MIN_BALANCE=100000`: la generación se detiene en la consulta de saldo, antes de subir la foto.

## API

Todos los endpoints `/api/**` exigen el header `X-Installation-Id: <UUID>`.

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/generations` | `multipart/form-data` con `image` (JPEG/PNG, ≤ 10 MB, lado menor ≥ 512 px). Devuelve `model/gltf-binary` |
| `GET` | `/api/v1/quota` | Créditos y cuota restantes, sin consumir nada |
| `GET` | `/actuator/health` | Estado del servicio (único endpoint de Actuator expuesto) |

```bash
curl -X POST http://localhost:8080/api/v1/generations \
  -H "X-Installation-Id: 3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80" \
  -F "image=@carro.jpg" \
  -o modelo.glb
```

**Ojo con los tiempos:** Tripo tarda 2–4 min y cada petición espera como máximo 85 s. Lo normal es que la primera responda `504` con `"resumable": true`; la generación sigue en segundo plano y **repetir la petición con la misma foto la retoma** sin crear (ni cobrar) otra tarea.

El catálogo completo de errores y headers está en [`docs/PRD.md`](docs/PRD.md) §15.

## Configuración

Todo se ajusta con variables de entorno (valores por defecto en [`application.yaml`](src/main/resources/application.yaml)).

| Variable | Por defecto | Para qué |
|---|---|---|
| `TRIPO_API_KEY` | — (obligatoria) | Clave de Tripo |
| `PORT` | `8080` | Puerto HTTP |
| `TUNAI_QUOTA_PER_IP` | `2` | Generaciones por día por IP |
| `TUNAI_QUOTA_GLOBAL_DAILY` | `2` | Generaciones por día en total |
| `TUNAI_CREDITS_INITIAL` / `TUNAI_CREDITS_PER_MODEL` | `45` / `15` | Créditos por instalación y costo por modelo |
| `TUNAI_TRIPO_MIN_BALANCE` | `40` | Saldo mínimo de Tripo exigido antes de generar |
| `TUNAI_MAX_CONCURRENT` | `2` | Generaciones simultáneas |

Los límites por defecto son los de la demo: para desarrollar súbelos, p. ej. `TUNAI_QUOTA_PER_IP=50 TUNAI_QUOTA_GLOBAL_DAILY=50`. La cuota vive en memoria y se reinicia al reiniciar el servicio.

## Pruebas y calidad

```bash
./mvnw verify        # Windows: .\mvnw.cmd verify
```

Ejecuta los tests, las reglas de arquitectura (ArchUnit), Spotless, Checkstyle y JaCoCo (mínimo 90 % de líneas en `domain`/`application` y 85 % en `adapter`). Los tests **no necesitan clave ni llaman a Tripo**: usan WireMock. Si Spotless falla por formato: `./mvnw spotless:apply`.

El CI (GitHub Actions) corre este mismo `verify` y el build de la imagen Docker en cada push y PR.

## Docker

```bash
docker build -t tunai-api .
docker run -p 8080:8080 -e TRIPO_API_KEY=tsk_... tunai-api
```

La imagen solo empaqueta (los tests corren en CI) y se ejecuta como usuario no root. Despliegue pensado para Render como *Web Service* con Docker (`SPRING_PROFILES_ACTIVE=prod` activa logs JSON).

## Arquitectura

Hexagonal (puertos y adaptadores); el dominio y la aplicación son Java puro y ArchUnit lo verifica en cada build.

```
src/main/java/com/tunai/api
├── domain        modelo y excepciones (sin Spring)
├── application   casos de uso y puertos
├── adapter
│   ├── in.web    controladores, errores, filtro de traceId
│   └── out       tripo · quota · imageio · concurrency · metrics
└── config        @ConfigurationProperties validadas y ensamblado de beans
```

Para cambiar de proveedor o de almacén de cuota basta con implementar su puerto (`ModelGeneratorPort`, `QuotaStorePort`).

## Documentación

La especificación completa (requisitos, reglas de negocio, contrato de la API y decisiones) está en [`docs/PRD.md`](docs/PRD.md).
