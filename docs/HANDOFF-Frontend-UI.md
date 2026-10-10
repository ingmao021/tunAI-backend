# Handoff al frontend — UI de TunAI Desktop

| Campo | Valor |
|---|---|
| Para | Repositorio **frontend** (TunAI Desktop, JavaFX) |
| Desde | Repositorio backend (`docs/PRD.md` v1.1) |
| Fecha | 2026-10-09 |
| Fecha de presentación | 9 de noviembre de 2026 |
| Objetivo | Construir la interfaz que ve el usuario al instalar y abrir el `.exe` |

> **Prioridad de fuentes:** si algo de este documento contradice `PRD-TunAI-Frontend.md`, manda el PRD del frontend, **salvo el contrato de la API (§6)**, que es oficial y está en `PRD.md` §15 del backend.
> Los puntos marcados **PENDIENTE** no están decididos. No los inventes: déjalos detrás de una interfaz o pregunta.

---

## 1. Contexto en 30 segundos

- El usuario toma una foto de su carro, la app la envía al backend, y el backend devuelve un **modelo 3D (GLB)** generado por Stable Fast 3D (Stability AI).
- El usuario ve el carro en 3D, le **cambia el color** y lo guarda en su **garaje local**.
- **Presupuesto real: 2 generaciones en total** (créditos gratuitos). Una de ensayo y una en vivo en la demo. Por eso:
  - la app **nunca** reintenta automáticamente un `POST` de generación;
  - el usuario debe ver claramente que cada generación consume cuota;
  - todo el desarrollo se hace contra el backend con el proveedor `fake`, que devuelve un GLB de ejemplo.
- No hay cuentas ni login. Cada instalación se identifica con un UUID propio.

---

## 2. Stack técnico

| Necesidad | Tecnología |
|---|---|
| Lenguaje y build | Java 21 + Maven |
| Interfaz | OpenJFX 21 LTS + `javafx-maven-plugin`; FXML + CSS |
| Leer el GLB | JglTF `de.javagl:jgltf-model:2.0.4` |
| Visor 3D | `SubScene` + `TriangleMesh` + `MeshView` + `PhongMaterial` (nativo JavaFX) |
| Recoloreado | `WritableImage` + `PixelReader` / `PixelWriter` sobre la textura del modelo |
| HTTP | `java.net.http.HttpClient` (timeout total 120 s, sin reintentos en `POST`) |
| JSON | Jackson |
| ¿Es un vehículo? | ONNX Runtime para Java (`com.microsoft.onnxruntime:onnxruntime`, última versión estable, fijada en el `pom.xml`) + MobileNetV2 entrenado en ImageNet (formato `.onnx`, ~14 MB, incluido en los recursos de la app). Ver §5.3.1 |
| Pruebas | JUnit 5 + TestFX |
| Instalador | `jpackage` → `.exe` con `--win-shortcut --win-menu --win-dir-chooser` |
| Paquete raíz | `com.tunai.desktop` |

**No usar:**
- `WebView` con three.js o `<model-viewer>`: el WebView de JavaFX no soporta WebGL.
- jMonkeyEngine o LWJGL embebidos: demasiado costosos para el plazo.

---

## 3. Restricción clave del visor 3D

El visor **solo** debe soportar el formato que devuelve SF3D:
- **1 malla**, **1 material**, **1 textura de color base** (`baseColorTexture`) con UV y **sin iluminación** horneada;
- sin extensiones `KHR_*` obligatorias.

Si llega un GLB que no cumple esto, se muestra el error "Modelo no compatible" (§7) y **no se intenta renderizarlo a medias**.

> El backend en modo `fake` hoy devuelve **CarConcept** (97 mallas, 29 materiales). **No cumple** este formato y sirve solo para probar el flujo HTTP. Para desarrollar el visor se necesita un GLB de una sola textura (el del ensayo real, o un modelo de prueba de una sola textura).

**Problemas conocidos al convertir de GLB a JavaFX:**
1. En glTF el eje Y apunta hacia arriba y en JavaFX hacia abajo → rotar 180° sobre X.
2. Las caras de `TriangleMesh` van en formato `p0,t0,p1,t1,p2,t2`; en glTF el índice de posición y el de UV son el mismo, así que cada valor se repite.
3. glTF y JavaFX usan el mismo origen de UV (arriba a la izquierda): no invertir V salvo que la prueba muestre la textura volteada.
4. Usar `VertexFormat.POINT_NORMAL_TEXCOORD` con las normales del GLB, para que el modelo no se vea "facetado".
5. Con 1 sola malla **no se pueden reemplazar los rines** como pieza aparte. Ver §5.5.

---

## 4. Datos locales

Todo vive en `%APPDATA%\TunAI\`:

```
%APPDATA%\TunAI\
├── installation-id          (UUID v4, se crea en el primer inicio y no cambia)
├── settings.json            (consentimiento aceptado + fecha, última carpeta usada)
└── garage\
    └── <vehicleId>\
        ├── original.glb     (tal como llegó del backend)
        ├── texture-edited.png (textura recoloreada, si hay)
        ├── thumbnail.png    (captura del visor, 256×256)
        └── vehicle.json     (nombre, fecha, color aplicado, versión)
```

- Si `installation-id` se borra, se genera uno nuevo. No pasa nada grave: solo la cuota se cuenta aparte.
- El garaje **nunca** se sube al servidor.

---

## 5. Pantallas

Flujo general:

```
Primer inicio ──▶ Garaje (inicio) ──▶ Nuevo vehículo ──▶ Generando ──▶ Visor / Editor
                       ▲                                                   │
                       └──────────────────── Guardar ◀─────────────────────┘
```

Ventana: mínimo 1280×720, redimensionable. Tema claro por defecto. Todo el texto en español.

### 5.1 Primer inicio: bienvenida y consentimiento

Aparece **solo la primera vez** o hasta que el usuario acepte.

```
┌──────────────────────────────────────────────────────────┐
│                      [logo] TunAI                        │
│   Mira tu carro en 3D y pruébale colores nuevos.         │
│                                                          │
│   ¿Cómo funciona?                                        │
│   ① Sube una foto de tu carro                            │
│   ② La IA crea un modelo 3D (≈ 1 minuto)                 │
│   ③ Cámbiale el color y guárdalo en tu garaje            │
│                                                          │
│   ┌────────────────────────────────────────────────────┐ │
│   │ Tu foto se envía a Stability AI (fuera de Colombia)│ │
│   │ solo para generar el modelo. TunAI no la guarda.   │ │
│   │ [Ver detalles]                                     │ │
│   └────────────────────────────────────────────────────┘ │
│   ☐ Acepto el envío de mis fotos para generar modelos    │
│                                                          │
│                    [ Empezar ]                           │
└──────────────────────────────────────────────────────────┘
```

- `Empezar` se habilita solo con la casilla marcada.
- El texto legal definitivo está **PENDIENTE** (Ley 1581, art. 26; backend P-BE-06). Mientras tanto, dejarlo en un archivo de recursos fácil de reemplazar.
- Se guarda `consentAcceptedAt` en `settings.json`.

### 5.2 Garaje (pantalla de inicio)

```
┌─ TunAI ─────────────────────────────────── ● Servidor listo  Hoy: 1 de 2 ─┐
│                                                                           │
│  Mi garaje                                        [ + Nuevo vehículo ]    │
│                                                                           │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                                    │
│  │ [mini]  │  │ [mini]  │  │ [mini]  │                                    │
│  │ Mazda   │  │ Twingo  │  │ Spark   │                                    │
│  │ 08 oct  │  │ 09 oct  │  │ 09 oct  │                                    │
│  └─────────┘  └─────────┘  └─────────┘                                    │
│                                                                           │
│  (vacío) "Aún no tienes vehículos. Crea el primero con + Nuevo vehículo"  │
└───────────────────────────────────────────────────────────────────────────┘
```

- **Indicador del servidor** (arriba a la derecha):
  - `○ Conectando…` (gris) mientras responde `GET /actuator/health`;
  - `● Servidor listo` (verde) con `200`;
  - `● Sin conexión` (rojo) si falla. Se reintenta cada 15 s.
- **Calentamiento:** al abrir la app se llama de inmediato a `GET /actuator/health`. El backend en Render tarda **~1 minuto** en despertar si estaba dormido. La app debe seguir funcionando mientras tanto (el garaje es local).
- **Contador de cuota:** viene de `GET /api/v1/quota` y se muestra como `Hoy: {limit - remaining} de {limit}`. Con `remaining = 0`, mostrar `Sin generaciones hoy · se renuevan a las 00:00`.
- **Tarjeta:** clic abre el visor. Menú contextual: Renombrar, Exportar, Eliminar (con confirmación).
- `+ Nuevo vehículo` está deshabilitado (con tooltip explicando por qué) si `remaining = 0` o si no hay consentimiento.

### 5.3 Nuevo vehículo

```
┌─ Nuevo vehículo ──────────────────────────────────────────── [← Garaje] ─┐
│                                                                          │
│  ┌──────────────────────────────┐   Consejos para una buena foto         │
│  │                              │   ✓ Carro completo y centrado          │
│  │   Arrastra tu foto aquí      │   ✓ Vista de 3/4 (frente y lateral)    │
│  │   o  [ Elegir archivo ]      │   ✓ Buena luz, sin sombras fuertes     │
│  │   JPG o PNG · máx. 10 MB     │   ✓ Fondo despejado                    │
│  │                              │   ✗ Fotos de interiores o de detalles  │
│  └──────────────────────────────┘                                        │
│                                                                          │
│  Nombre: [ Mi carro                  ]                                   │
│                                                                          │
│  ⚠ Esto usará 1 de tus 2 generaciones de hoy.                            │
│                                         [ Cancelar ] [ Generar modelo ]  │
└──────────────────────────────────────────────────────────────────────────┘
```

**Validación local antes de enviar** (la misma que hace el backend, para no gastar una petición):

| Regla | Mensaje |
|---|---|
| Firma de bytes JPEG (`FF D8 FF`) o PNG (`89 50 4E 47`); no confiar en la extensión | "Solo se aceptan fotos JPG o PNG." |
| Tamaño ≤ 10 MB | "La foto pesa más de 10 MB. Usa una de menor tamaño." |
| Lado menor ≥ 512 px | "La foto es muy pequeña. Necesita al menos 512 px por lado." |

- Al elegir la foto se muestra una vista previa ajustada al recuadro.
- `Generar modelo` se habilita solo si la foto pasa la validación local **y** el clasificador detecta un vehículo (§5.3.1).
- `Generar modelo` muestra un **diálogo de confirmación** antes de enviar: "Vas a usar 1 de tus generaciones. ¿Continuar?".

#### 5.3.1 Verificación de vehículo (clasificador local)

**Por qué:** Stable Fast 3D no sabe qué es un carro. Modela el objeto principal de **cualquier** foto (un perro, una taza, una persona) y **cobra la generación**. Con solo 2 generaciones, una foto equivocada se pierde la demo. Ver backend RN-16 y R-BE-12.

**Cómo:**
1. Al elegir la foto (y después de la validación local), correr el clasificador en un `Task`, fuera del hilo de la UI.
2. Preprocesar la imagen así:
   - redimensionarla a 224×224;
   - convertirla a RGB;
   - normalizarla con la media y la desviación estándar de ImageNet (`mean = [0.485, 0.456, 0.406]`, `std = [0.229, 0.224, 0.225]`);
   - pasarla al tensor en orden `NCHW` (`[1, 3, 224, 224]`).

   Confirmar estos valores con la ficha del modelo que se descargue.
3. Aplicar `softmax` a la salida (1000 clases) y tomar las **5 más probables**.
4. Sumar la probabilidad de las clases del top 5 que estén en la lista de vehículos.
5. Si la suma es **≥ umbral** (inicial **0,40**, configurable), se considera vehículo.

**Clases de vehículo** (por **nombre** de la etiqueta de ImageNet; el índice se obtiene del archivo de etiquetas que acompaña al modelo, **no** se escribe a mano):
`sports car`, `convertible`, `cab` (taxi), `beach wagon` (station wagon), `jeep`, `limousine`, `minivan`, `Model T`, `pickup`, `racer` (race car), `police van`, `ambulance`, `minibus`, `moving van`, `tow truck`, `recreational vehicle`.

- Quedan **fuera**:
  - camiones grandes, buses, trenes y motos, porque el producto es para carros;
  - `car wheel` y `grille` (rin y parrilla), porque son fotos de detalle, que dan modelos malos.
- La lista vive en un archivo de recursos (`vehicle-classes.txt`) para poder ajustarla sin recompilar.

**UI** (debajo de la vista previa):

```
  ◌ Revisando la foto…
  ✓ Vehículo detectado (87 %)                    → Generar habilitado
  ✗ No detectamos un vehículo en la foto.         → Generar deshabilitado
    Prueba con otra donde el carro se vea completo.
```

**Reglas:**
- El clasificador corre **sin conexión** y no consume cuota.
- **No** hay botón "usar de todas formas" en la UI: con 2 generaciones, un falso positivo cuesta más que un falso negativo.
- Si el clasificador rechaza una foto válida de un carro (falso negativo), se puede desactivar con `classifier.enabled=false` en `settings.json`. Es una salida de emergencia para la demo, no algo visible para el usuario.
- Si el modelo `.onnx` no carga (archivo dañado o error de ONNX Runtime), se registra en el log y se **bloquea** la generación con el mensaje `CLASSIFIER_UNAVAILABLE` (§7). No se envía la foto sin verificar.

**Calibración:**
- Probar con unas **30 fotos**: 15 de carros (distintos ángulos, colores y fondos) y 15 que no lo son (personas, animales, objetos, motos, interiores de carro).
- Ajustar el umbral para que **ninguna** foto que no sea de carro pase. Tolerar algunos falsos negativos en carros con fotos raras.
- Es gratis: no llama a Stability.
- Registrar el resultado en una tabla (foto, top 5, suma y decisión) para mostrarlo en la presentación si preguntan.

**Licencia del modelo:** verificar la licencia de los pesos de MobileNetV2 que se descarguen (por ejemplo, del ONNX Model Zoo) y agregarla a "Acerca de" (§5.6). **PENDIENTE DE VALIDAR**.

### 5.4 Generando

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│                    [miniatura de la foto]                │
│                                                          │
│    ✓ Foto validada                                       │
│    ◌ Despertando el servidor…  (puede tardar ~1 min)     │
│    ○ Generando modelo 3D                                 │
│    ○ Preparando visor                                    │
│                                                          │
│              ▓▓▓▓▓▓▓▓░░░░░░░░  0:42                      │
│                                                          │
│   No cierres la app. Si cancelas, la generación podría   │
│   contarse igual.                     [ Cancelar espera ]│
└──────────────────────────────────────────────────────────┘
```

- Si el servidor ya estaba "listo", se omite el paso "Despertando el servidor".
- La barra es **indeterminada** (no hay progreso real) y va con un cronómetro.
- Timeout total del cliente: **120 s** → error `CLIENT_TIMEOUT` (§7).
- `Cancelar espera` solo deja de esperar; el backend puede terminar la generación de todas formas. Se avisa en el texto.
- **Nunca** reintentar solo. Si falla, el usuario decide si vuelve a intentar.
- Con éxito: se leen los headers `X-Quota-*`, se actualiza el contador, se valida el GLB (§3) y se abre el visor.

### 5.5 Visor / Editor

```
┌─ Mazda ────────────────────────────────────── [← Garaje]  [ Guardar ] ─┐
│ ┌──────────────────────────────────────────┐ ┌─ Personalizar ────────┐ │
│ │                                          │ │ Pintura               │ │
│ │                                          │ │ ● ● ● ● ● ● ● ●       │ │
│ │              [ modelo 3D ]               │ │ [ Color personalizado]│ │
│ │                                          │ │ Intensidad ──●───     │ │
│ │                                          │ │                       │ │
│ │                                          │ │ [ Restaurar original ]│ │
│ │                                          │ │                       │ │
│ │  ⟲ Girar  ⊕ Zoom  ⌂ Vista inicial  ▶ Auto│ │ Comparar              │ │
│ └──────────────────────────────────────────┘ │ [ Antes | Después ]   │ │
│                                              │                       │ │
│                                              │ Exportar              │ │
│                                              │ [ Imagen PNG ]        │ │
│                                              │ [ Modelo GLB ]        │ │
│                                              └───────────────────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

**Cámara:**
- arrastrar con el botón izquierdo: girar (órbita);
- rueda: zoom, con límites para no atravesar el modelo;
- arrastrar con el botón derecho: desplazar;
- `⌂`: vuelve a la vista inicial (3/4 frontal);
- `▶ Auto`: rotación automática lenta, útil para la demo.

Iluminación: `AmbientLight` más una `PointLight` suave. La textura de SF3D viene sin iluminación, así que la escena aporta la luz.

**Pintura:**
- Paleta de 8 colores más un `ColorPicker` para color personalizado.
- El recoloreado cambia el tono (H) de los píxeles de la **pintura** en la textura y conserva la luminosidad, para que no se pierdan los detalles.
- **Detectar qué píxeles son pintura es la parte de mayor riesgo.** Enfoque inicial: tomar el color dominante de la textura y cambiar solo los píxeles cercanos a él. Hay que ajustarlo con el GLB del ensayo real. Dejarlo detrás de una interfaz (`PaintMaskStrategy`) para poder cambiarlo.
- `Intensidad` mezcla el color nuevo con el original.
- El recoloreado corre fuera del hilo de la UI (`Task`) y muestra el resultado al terminar. No debe congelar la ventana.

**Comparar:** alterna entre la textura original y la editada (o pantalla dividida, si da el tiempo).

**Rines:** **PENDIENTE**. Como SF3D devuelve una sola malla, no se pueden reemplazar como pieza aparte; a lo sumo se pueden recolorear como una zona de la textura. Confirmarlo con `PRD-TunAI-Frontend.md` §17 antes de implementar.

**Exportar:**
- `Imagen PNG`: captura del visor (`SubScene.snapshot`).
- `Modelo GLB`: el GLB original con la textura editada reemplazada. Con JglTF: leer, reemplazar la imagen y escribir.

**Guardar:** escribe la carpeta del vehículo en el garaje (§4). Si hay cambios sin guardar y el usuario sale, se le pregunta.

### 5.6 Acerca de y créditos

Accesible desde un menú `?`:
- versión de la app;
- "Modelos 3D generados con Stable Fast 3D de Stability AI". El texto exacto de atribución que exige la licencia está **PENDIENTE DE VALIDAR**;
- licencias de las librerías (OpenJFX, JglTF, Jackson, ONNX Runtime) y del modelo MobileNetV2;
- ID de instalación, con botón **Copiar** (útil para soporte).

---

## 6. Contrato con el backend (resumen de `PRD.md` §15)

**Base URL:**
- desarrollo: `http://localhost:8080`;
- producción: `https://tunai-api-qd5x.onrender.com`.

Configurable con la variable de entorno `TUNAI_API_URL` o con `settings.json`.

**Headers en todo `/api/**`:**
- `X-Installation-Id: <UUID>` (obligatorio);
- `X-Client-Version: <semver>` (opcional, solo se registra).

| Método y ruta | Uso | Respuesta OK |
|---|---|---|
| `GET /actuator/health` | Calentamiento e indicador del servidor | `200 {"status":"UP"}` |
| `GET /api/v1/quota` | Contador de cuota | `200 {"limit":2,"remaining":1,"resetsAt":"2026-10-10T05:00:00Z"}` |
| `POST /api/v1/generations` | Generar (multipart, campo `image`) | `200`, `Content-Type: model/gltf-binary`, cuerpo GLB |

**Headers de cuota** en las respuestas de generación y cuota: `X-Quota-Limit`, `X-Quota-Remaining`, `X-Quota-Reset`. En `429` y `503` llega además `Retry-After` (en segundos).

**Errores:** `application/problem+json` con un campo `code` estable. La UI decide qué mostrar por `code`, **nunca** por el texto de `detail`.

---

## 7. Mensajes de error para el usuario

| `code` (HTTP) | Mensaje | Acción en la UI |
|---|---|---|
| `INVALID_INSTALLATION_ID` (400) | "Hubo un problema con la instalación. Reinicia la app." | Regenerar el ID si está corrupto |
| `INVALID_IMAGE` (400) | "No pudimos leer la foto. Prueba con otra." | Volver a Nuevo vehículo |
| `IMAGE_TOO_LARGE` (413) | "La foto pesa más de 10 MB." | Volver a Nuevo vehículo |
| `UNSUPPORTED_MEDIA_TYPE` (415) | "Solo se aceptan fotos JPG o PNG." | Volver a Nuevo vehículo |
| `PROVIDER_REJECTED` (422) | "La IA no pudo procesar esta foto. Prueba con otra donde el carro se vea completo." | Volver; **no consumió cuota** |
| `QUOTA_EXCEEDED` (429) | "Ya usaste tus generaciones de hoy. Se renuevan a las 00:00." | Deshabilitar `+ Nuevo vehículo` |
| `PROVIDER_ERROR` (502) | "El servicio de IA falló. No se descontó tu cuota." | Ofrecer `Reintentar` (manual) |
| `SERVICE_BUSY` (503) | "El servidor está ocupado. Intenta en {Retry-After} s." | Botón `Reintentar` con cuenta regresiva |
| `SERVICE_UNAVAILABLE` (503) | "El servicio no está disponible en este momento." | Volver al garaje |
| `PROVIDER_TIMEOUT` (504) | "La IA tardó demasiado. No se descontó tu cuota." | Ofrecer `Reintentar` (manual) |
| `INTERNAL_ERROR` (500) | "Ocurrió un error inesperado." | Volver al garaje |
| `BAD_REQUEST` (400), `NOT_FOUND` (404), `METHOD_NOT_ALLOWED` (405) | "Ocurrió un error inesperado." | Son errores de programación del cliente: registrar en log con el `traceId` y volver al garaje |
| Cualquier `code` desconocido | "Ocurrió un error inesperado." | Tratarlo como `INTERNAL_ERROR`; nunca fallar por un código nuevo |
| `CLIENT_TIMEOUT` (cliente) | "No hubo respuesta del servidor. Revisa tu conexión." | Volver al garaje |
| `NETWORK_ERROR` (cliente) | "Sin conexión con el servidor." | Volver al garaje |
| `UNSUPPORTED_MODEL` (cliente) | "El modelo recibido no es compatible con el visor." | Volver al garaje y registrar en log |
| `NOT_A_VEHICLE` (cliente) | "No detectamos un vehículo en la foto. Prueba con otra donde el carro se vea completo." | Quedarse en Nuevo vehículo; Generar deshabilitado; **no se llama al backend** |
| `CLASSIFIER_UNAVAILABLE` (cliente) | "No pudimos revisar la foto. Reinicia la app." | Generar deshabilitado; registrar en log |

Todos los errores incluyen un enlace pequeño **"Detalles"** que muestra el `traceId`, para poder buscarlo en los logs del backend.

---

## 8. Criterios de aceptación de la UI

| ID | Criterio |
|---|---|
| CA-UI-01 | Al instalar el `.exe` y abrirlo por primera vez aparece la pantalla de consentimiento; tras aceptar no vuelve a aparecer |
| CA-UI-02 | El garaje abre y funciona sin conexión con el servidor |
| CA-UI-03 | Al abrir la app se llama a `GET /actuator/health` y el indicador refleja el estado |
| CA-UI-04 | Una foto inválida (formato, tamaño, dimensiones) se rechaza **sin** llamar al backend |
| CA-UI-05 | Cada generación pide confirmación y nunca se reintenta automáticamente |
| CA-UI-06 | Cada `code` del catálogo §7 muestra su mensaje y su acción |
| CA-UI-07 | Un GLB con forma de SF3D (1 malla, 1 textura) se ve derecho, con textura y sin "facetado" |
| CA-UI-08 | Cambiar el color no congela la ventana y "Restaurar original" vuelve a la textura original |
| CA-UI-09 | Guardar, cerrar y reabrir la app conserva el vehículo con su color |
| CA-UI-10 | El contador de cuota coincide con los headers `X-Quota-*` después de cada generación |
| CA-UI-11 | La app instalada no requiere tener Java instalado (runtime incluido por `jpackage`) |
| CA-UI-12 | Una foto que no es de un vehículo (persona, animal, objeto) se rechaza con `NOT_A_VEHICLE` **sin** llamar al backend, y funciona sin conexión |
| CA-UI-13 | En el set de calibración (§5.3.1), **0** fotos que no son de carros pasan el clasificador; la tabla de resultados queda guardada |
| CA-UI-14 | El clasificador no congela la ventana y tarda menos de 1 s por foto en el PC de la presentación |

---

## 9. Orden sugerido de trabajo

1. Proyecto Maven + OpenJFX con una ventana que abre (ejecutando con `mvn javafx:run`).
2. Garaje local + pantalla de primer inicio.
3. Cliente HTTP: health, quota y generations contra el backend local en modo `fake`, con el catálogo de errores.
4. Pantallas Nuevo vehículo y Generando.
5. Clasificador de vehículos y su calibración (§5.3.1). **Debe estar listo antes del ensayo real**, para no gastar el crédito con una foto equivocada.
6. Visor 3D con un GLB de **una sola textura**.
7. Recoloreado (ajustarlo con el GLB del ensayo real).
8. Comparar, exportar y pulir para la demo.
9. Generar el `.exe` (§11), **solo** cuando todo lo anterior funcione.

## 10. Pendientes que afectan la UI

| Tema | Estado |
|---|---|
| Texto legal de consentimiento (Ley 1581) | PENDIENTE |
| Base URL de producción | Resuelta: `https://tunai-api-qd5x.onrender.com` |
| Rines: ¿recolorear zona o descartar? | PENDIENTE (PRD frontend §17) |
| Atribución exigida por la licencia de Stability | PENDIENTE DE VALIDAR |
| Detección de píxeles de pintura en la textura real | Se ajusta tras el ensayo |
| Logo, colores de marca y tipografía | PENDIENTE (no definido en el backend) |
| Umbral del clasificador de vehículos | Inicial 0,40; se ajusta en la calibración (§5.3.1) |
| Licencia de los pesos de MobileNetV2 | PENDIENTE DE VALIDAR |

---

## 11. Entrega: generar el `.exe`

**Solo cuando todo funcione.** Antes de empaquetar, verificar desde el IDE (`mvn javafx:run`) contra el backend que:
- todos los criterios CA-UI-01 a CA-UI-14 se cumplen, excepto CA-UI-11, que se verifica con el instalador;
- el flujo completo (foto → clasificador → generación → visor → recolorear → guardar) funciona contra el backend en Render en modo `fake`;
- la URL de producción del backend está configurada.

Si algo falla, **no** se genera el `.exe`: primero se corrige.

**Generar el instalador con `jpackage`** (viene con el JDK 21):

```powershell
mvn clean package
jpackage --type exe `
  --name TunAI `
  --app-version 1.0.0 `
  --vendor "TunAI" `
  --input target\app `
  --main-jar tunai-desktop.jar `
  --main-class com.tunai.desktop.App `
  --win-shortcut --win-menu --win-dir-chooser `
  --icon src\main\resources\icon.ico `
  --dest dist
```

- Ajustar `--input`, `--main-jar` y `--main-class` a la estructura real del proyecto. `target\app` debe contener el jar de la app y sus dependencias, incluidos los jars de OpenJFX para Windows.
- Para `--type exe` en Windows con JDK 21 hay que tener instalado **WiX Toolset 3.x**.
- El `.exe` queda en `dist\`.

**Verificar el instalador** en un PC **sin Java instalado** (o en el de la presentación):
1. Instalar el `.exe`. Como no está firmado, Windows SmartScreen mostrará una advertencia; es esperado: *Más información → Ejecutar de todas formas*.
2. Abrir la app desde el acceso directo y confirmar CA-UI-01 (consentimiento) y CA-UI-11 (no requiere Java).
3. Repetir el flujo completo en modo `fake`.
