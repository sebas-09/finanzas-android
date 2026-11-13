# Base de interfaz Android F0

Skill aplicada: ui-ux-pro-max. La búsqueda de diseño devolvió Minimalism & Swiss Style, paleta financiera navy y tipografía legible; la sugerencia de landing/conversión no corresponde a una app personal y se descartó. La búsqueda jetpack-compose confirmó semántica y recolección de estado/navegación sin eventos reproducidos desde StateFlow.

- Material 3 nativo con fuente del sistema, sin descargar fuentes ni imágenes. Primario #0F172A, secundario #1E3A8A, fondo #F8FAFC; tokens equivalentes claros/oscuros en core/design-system.
- Texto en sp mediante tipografía Material; sin alturas de texto fijas. Contenido vertical desplazable para tipografía ampliada y pantallas pequeñas.
- Botones Material con objetivos táctiles estándar y etiquetas visibles. Encabezados marcados mediante semantics.heading. No hay controles solo por color ni iconos sin texto.
- Scaffold respeta insets del sistema y navegación Atrás. Inicio ofrece accesos a las siete secciones secundarias; bienvenida suma la novena pantalla. No se crean nueve pestañas ni una barra inferior sobrecargada.
- F0 muestra captura no activada y estados vacíos reales. La comprobación de BD tiene estados carga/listo/error y reintento; error no borra datos.
- Pantallas funcionales, formularios, filtros y errores por campo se incorporan al desarrollar sus features. La verificación física TalkBack y texto grande sigue siendo un control manual, no una garantía derivada de compilar.

