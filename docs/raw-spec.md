# Farm Math Builder — Raw MVP Spec (input, unrefined)

## Decided answers to open questions
1. Target ages: BOTH 6-9 and 10-12 (difficulty scales by age selection)
2. Engine: Unity (chosen for 3D low-poly isometric style — see reference image
   C:\Users\moldovan\Downloads\3dExample.png, a FarmVille-style isometric farm
   scene with a red barn, low-poly wheat/carrot fields, wooden fences, a truck,
   dirt roads)
3. Multiplayer: NOT in MVP — single player only
4. Backend: Local only for MVP (SQLite/Room), no cloud sync
5. Timeline: user originally wanted "this week" but agreed the full spec
   including custom 3D low-poly art is not realistically buildable in 1 week.
   Treat the original 4-week MVP roadmap (see below) as the real target;
   "this week" was aspirational and should NOT constrain scope.

---

1. VISIÓN GENERAL
Juego educativo tipo FarmVille para Android enfocado en niños (6-12 años). Sistema de construcción de granja con mecánicas de progresión basadas en resolución de ejercicios matemáticos (sumas, restas). El juego combina gestión de recursos con mini-juegos educativos como moneda de desbloqueo.

2. MECÁNICAS PRINCIPALES
2.1 Sistema de Construcción en Grid
    - Mapa dividido en celdas hexagonales o cuadradas (6x8 inicial, expandible)
    - Celda central: Edificio principal (Granja) - no editable
    - Cada celda ocupa un slot único - sin solapamientos
    - Radio de influencia visual del edificio central (muestra zona constructible)
    - Objetos destructibles (campos, caminos) pueden eliminarse y reconstruirse sin costo
    - Visualizacion clara de celdas disponibles vs. ocupadas vs. bloqueadas
2.2 Sistema de Cultivos (Ciclo de Crecimiento)
    - Cultivo inicial: TRIGO
        - Fases de crecimiento: Semilla -> Brote -> Planta -> Maduro (4 fases visuales)
        - Tiempo real: 10 minutos para madurar
        - Estados visuales claros: animacion de crecimiento progresivo
        - Al madurar: Notificacion visual/sonora + popup de "Listo para cosechar"
        - Accion de cosecha: Toca el cultivo maduro -> +5 monedas (Trigo cosechado)
        - Campo vacio tras cosecha (disponible para nueva siembra)
        - Limite diario: 5 campos de trigo gratuitos por dia (reseteado a las 00:00)
2.3 Moneda & Sistema de Desbloqueo de Recursos
    - Moneda principal: TRIGO (grano de trigo)
        - Se obtiene por cosecha (5 trigo/cultivo maduro)
        - Se usa para desbloquear nuevos campos gratuitos diarios
        - No se puede comprar con dinero real (anti-pay-to-win para MVP)
    - Desbloqueador: RESPUESTAS CORRECTAS A EJERCICIOS
        - Cada ejercicio matematico correcto = +1 campo de trigo extra (por encima de 5 diarios)
        - Los ejercicios se acceden mediante boton flotante/menu dentro del juego
        - Dificultad escalada por edad seleccionada (facil/normal/dificil)
        - Contador visible: "Campos extra disponibles hoy: 3/10"
        - Los ejercicios pueden resolverse varias veces por dia (sin limite por sesion)
2.4 Sistema de Caminos
    - Decorativos pero necesarios para la progresion estetica
    - Se desbloquean progresivamente con cada nuevo campo de trigo construido
    - Tipos: camino recto, esquina, interseccion
    - Baja friccion: recursos gratuitos, solo requieren click para colocar
    - Objetivo: Fomentar diseno y creatividad visual de la granja
2.5 Interrupcion de Construccion
    - Si hay un cultivo en construccion, el jugador puede tocar el campo -> "Cancelar crecimiento"
    - Accion: Vuelve a estado semilla + devuelve automaticamente los recursos (si los hay) al inventario
    - Visualizacion: Icono de "X" o gesto deslizable sobre cultivos en crecimiento
    - Animacion: El cultivo se encoge/desvanece visualmente

3. CICLOS DE GAMEPLAY & PROGRESION
3.1 Ciclo Diario
    - Reset de campos gratuitos: 00:00 UTC
    - Contador visual en pantalla principal: "Campos gratis hoy: 5/5"
    - Notificacion al reiniciar contador: "5 nuevos campos disponibles"
    - Objetivo: Mantener jugadores enganchados con ciclos predecibles
3.2 Ciclo Semanal
    - Tracker de progreso: Campos cosechados esta semana
    - Logro opcional: "Cosecha 20 campos" -> Recompensa visual (medalla/badge)
    - Motivacion: Ver estadisticas de progreso
3.3 Progresion Vertical (Futuro)
    - Niveles de granja (1-50): Desbloquea nuevas plantas, caminos especiales
    - Nivel actual mostrado prominentemente (ej. "Nivel 5 - Agricultor")
    - XP por acciones: Cosechar (+10 XP), Resolver ejercicio (+20 XP)

4. INTERFAZ & UX
4.1 Pantalla Principal
    - Vista isometrica/3D del mapa de granja
    - Zoom/pan tactil (2 dedos zoom, drag pan)
    - Botones flotantes (FAB) accesibles: Resolver ejercicio, Stats, Configuracion
4.2 Construccion de Cultivos (Flujo)
    1. Selecciona celda vacia
    2. Popup: "Construir Trigo" con icono/costo
    3. Si hay campos gratuitos disponibles -> Boton verde "Construir gratis"
    4. Si no hay gratuitos: Mostrar costo en monedas / opcion "Resuelve un ejercicio para +1 campo gratis"
    5. Confirmacion -> Inicia animacion de crecimiento
4.3 Gestion de Inventario
    - HUD esquina superior derecha: icono Trigo + numero, campos gratis disponibles hoy
    - Expandible: Toque en HUD -> Panel de inventario detallado
4.4 Notificaciones
    - En-juego: cultivo listo, campos reseteados, ejercicio correcto (confetti)
    - Push (opcional MVP+1): "Tu trigo esta listo" 6h despues, solo si offline

5. SISTEMA DE EJERCICIOS MATEMATICOS
5.1 Mini-juego
    - Modal, no interrumpe gameplay
    - Pregunta central + 4 opciones multiple choice
    - Feedback inmediato correcto/incorrecto, sin penalizacion, retry ilimitado
    - Dificultad por edad: Facil (sumas/restas 1-10), Normal (1-50), Dificil (1-100 + multiplicacion simple)
5.2 Gamificacion
    - Racha de respuestas correctas
    - Contador de ejercicios resueltos hoy
    - Sin castigo por errores

6. PERSISTENCIA & DATOS
6.1 Se guarda: estado de cada celda, inventario, campos gratis usados hoy, undo buffer (opcional), timestamp ultimo reset diario, nivel jugador (futuro), ejercicios resueltos
6.2 Base de datos local: SQLite/Room (Android nativo), backup automatico cada 5 min de inactividad, soporta sync cloud futuro

7. ARTE & VISUAL
7.1 Estilo: 3D low-poly estilo FarmVille, paleta verdes/marron/cielo azul claro, iconografia simple y amigable, animacion de crecimiento fluida, sonido ambiente opcional
7.2 Assets iniciales MVP: 1 modelo cultivo trigo (4 fases), 1 modelo edificio central, 4-6 tipos de camino, texturas hierba/tierra/cielo, iconos UI

8. ONBOARDING
8.1 Tutorial: bienvenida + seleccion edad (6-9, 10-12, 13+) -> construir primer campo -> esperar crecimiento -> cosechar -> resolver ejercicios -> disenar granja libremente
8.2 Indicadores: boton pulsante en primer campo, tooltip "toca para cosechar", highlight celdas construibles

9. CONFIGURACION & ACCESIBILIDAD
    - Selector edad al iniciar (determina dificultad ejercicios)
    - Volumen musica/SFX separado, modo sin sonido, tamano texto configurable, alto contraste, modo sin notificaciones

10. MONETIZACION (POST-MVP, NO INCLUIR AHORA)
    - Sin ads ni microtransacciones en MVP; futuro: temas cosmeticos premium; nunca pay-to-win

11. CONSTRAINTS TECNICOS
    - Min SDK Android 8.0 (API 26), Target SDK Android 14+
    - Kotlin (recomendado)
    - Motor: Unity (decidido)
    - WorkManager + Handler para timers persistentes en background
    - ViewModel + LiveData o Jetpack Compose (nota: si se usa Unity como motor de juego, la capa nativa Android es minima/wrapper; evaluar si Compose/ViewModel aplica solo a shell nativo o si todo vive en Unity/C#)

12. ROADMAP
MVP (Semanas 1-4): grid + 1 cultivo, sistema crecimiento 10 min, cosecha + inventario, 5 campos gratis/dia, mini-juego sumas, persistencia SQLite, tutorial
MVP+1 (Semanas 5-8): caminos + decoracion, restas, multiples dificultades, notificaciones locales, logros/badges, graficas mejoradas
v1.0 (Semanas 9-12): segunda planta (maiz), niveles de granja (XP), cloud sync Firebase, multijugador local opcional, iOS
