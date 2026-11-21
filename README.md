<p align="center">
  <img src="docs/assets/logo.png" alt="TunAI" width="380">
</p>

<h1 align="center">TunAI API</h1>

<p align="center">
  Convierte la foto de un carro en un modelo 3D (<code>.glb</code>) con <a href="https://developers.tripo3d.ai/en/docs">Tripo</a>,<br>
  protegiendo la clave del proveedor y controlando el gasto con cuotas.
</p>

<p align="center">
  <a href="https://github.com/ingmao021/tunAI-backend/actions/workflows/ci.yml"><img src="https://github.com/ingmao021/tunAI-backend/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21">
  <img src="https://img.shields.io/badge/Spring_Boot-4.1-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 4.1">
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?logo=docker&logoColor=white" alt="Docker">
</p>

---

`foto JPEG/PNG` → validación y cuota → Tripo → `modelo GLB`. Sin base de datos: nada se guarda en disco. Cliente oficial: [tunAI-desktop](https://github.com/ingmao021/tunAI-desktop).

## Inicio rápido

Necesitas **JDK 21+** y una [clave de API de Tripo](https://developers.tripo3d.ai/en/docs). Maven no hace falta (trae `mvnw`).

```bash
git clone https://github.com/ingmao021/tunAI-backend.git && cd tunAI-backend

export TRIPO_API_KEY=tsk_...          # Windows (PowerShell): $env:TRIPO_API_KEY = "tsk_..."
./mvnw spring-boot:run                # Windows: .\mvnw.cmd spring-boot:run

curl http://localhost:8080/actuator/health    # {"status":"UP"}
```

> La app **no arranca sin `TRIPO_API_KEY`**. Nunca la versiones: usa la variable de entorno o `src/main/resources/application-local.yaml` (ignorado por git) con `-Dspring-boot.run.profiles=local`.

> ⚠️ **Cada generación exitosa gasta ~40 créditos reales de Tripo.** Para probar sin gastar, arranca con `TUNAI_TRIPO_MIN_BALANCE=100000`: se detiene en la consulta de saldo, antes de subir la foto.

## API

Los endpoints `/api/**` exigen el header `X-Installation-Id: <UUID>`.

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/v1/generations` | `multipart/form-data` con `image` (JPEG/PNG, ≤ 10 MB, lado menor ≥ 512 px) → `model/gltf-binary` |
| `GET` | `/api/v1/quota` | Créditos y cuota restantes, sin consumir nada |
| `GET` | `/actuator/health` | Estado del servicio |

```bash
curl -X POST http://localhost:8080/api/v1/generations \
  -H "X-Installation-Id: 3f1c2a9e-6d0b-4e8a-9f7c-2b5d1e4a6c80" \
  -F "image=@carro.jpg" -o modelo.glb
```

Tripo tarda 2–4 min y cada petición espera máximo 85 s: lo normal es recibir `504` con `"resumable": true`. **Repite la petición con la misma foto** y se retoma la generación sin crear (ni cobrar) otra. Los errores usan `application/problem+json` con un `code` estable ([catálogo en `docs/PRD.md` §15](docs/PRD.md)).

## Configuración

Todo por variables de entorno (defaults en [`application.yaml`](src/main/resources/application.yaml)). Las principales:

| Variable | Por defecto | Para qué |
|---|---|---|
| `TRIPO_API_KEY` | — (obligatoria) | Clave de Tripo |
| `PORT` | `8080` | Puerto HTTP |
| `TUNAI_QUOTA_PER_IP` / `TUNAI_QUOTA_GLOBAL_DAILY` | `2` / `2` | Generaciones por día por IP / en total |
| `TUNAI_MAX_CONCURRENT` | `2` | Generaciones simultáneas |

Los límites son los de la demo: para desarrollar súbelos (`TUNAI_QUOTA_PER_IP=50 TUNAI_QUOTA_GLOBAL_DAILY=50`). La cuota vive en memoria y se reinicia con el servicio.

## Pruebas y Docker

```bash
./mvnw verify        # tests + ArchUnit + Spotless + Checkstyle + JaCoCo (Windows: .\mvnw.cmd verify)
./mvnw spotless:apply  # si falla el formato

docker build -t tunai-api .
docker run -p 8080:8080 -e TRIPO_API_KEY=tsk_... tunai-api
```

Los tests **no necesitan clave ni llaman a Tripo** (usan WireMock). El CI corre el mismo `verify` y construye la imagen en cada push y PR.

## Arquitectura

Hexagonal (puertos y adaptadores), verificada con ArchUnit en cada build: `domain` y `application` son Java puro; `adapter` (`in.web`, `out`) y `config` rodean el núcleo. Cambiar de proveedor o de almacén de cuota es implementar `ModelGeneratorPort` o `QuotaStorePort`.

📄 Especificación completa (requisitos, reglas de negocio y contrato de la API): [`docs/PRD.md`](docs/PRD.md)
