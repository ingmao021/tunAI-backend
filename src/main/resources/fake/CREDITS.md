# vehicle-sample.glb

- Modelo: Renault Duster generado con Stable Fast 3D de código abierto (https://github.com/Stability-AI/stable-fast-3d), ejecutado en local con los parámetros por defecto (textura 1024, `foreground_ratio` 0.85, sin remallado)
- Licencia del modelo generador: Stability AI Community License
- Formato: el mismo que devuelve la API de Stability (1 malla, 1 primitiva, 1 material con textura de color base y mapa de normales, sin extensiones obligatorias), que es lo que acepta el visor del cliente
- Uso: respuesta del adaptador `fake` (RF-BE-021); solo para desarrollo y pruebas del flujo completo, no se muestra como resultado de la IA
- Antes se usaba Car Concept (Khronos, CC BY 4.0), que tiene 97 mallas y el visor rechaza
