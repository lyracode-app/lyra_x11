"""Exercise guest Mesa virpipe without creating an X11 window."""

import ctypes as c
import re
import sys


def bind(library, name, result, *parameters):
    function = getattr(library, name)
    function.restype = result
    function.argtypes = parameters
    return function


def probe():
    egl = c.CDLL("libEGL.so.1")
    gl = c.CDLL("libGLESv2.so.2")
    handle, integer, boolean = c.c_void_p, c.c_int, c.c_uint
    integers = c.POINTER(integer)
    egl_error = bind(egl, "eglGetError", integer)
    get_display = bind(egl, "eglGetDisplay", handle, handle)
    initialize = bind(egl, "eglInitialize", boolean, handle, integers, integers)
    choose_config = bind(egl, "eglChooseConfig", boolean, handle, integers,
                         c.POINTER(handle), integer, integers)
    bind_api = bind(egl, "eglBindAPI", boolean, integer)
    create_context = bind(egl, "eglCreateContext", handle, handle, handle, handle, integers)
    create_pbuffer = bind(egl, "eglCreatePbufferSurface", handle, handle, handle, integers)
    make_current = bind(egl, "eglMakeCurrent", boolean, handle, handle, handle, handle)
    destroy_surface = bind(egl, "eglDestroySurface", boolean, handle, handle)
    destroy_context = bind(egl, "eglDestroyContext", boolean, handle, handle)
    terminate = bind(egl, "eglTerminate", boolean, handle)
    get_string = bind(gl, "glGetString", c.c_char_p, c.c_uint)
    clear_color = bind(gl, "glClearColor", None, *([c.c_float] * 4))
    clear = bind(gl, "glClear", None, c.c_uint)
    read_pixels = bind(gl, "glReadPixels", None, integer, integer, integer,
                       integer, c.c_uint, c.c_uint, handle)
    gl_error = bind(gl, "glGetError", c.c_uint)

    def require(success, operation):
        if not success:
            raise RuntimeError(f"{operation} failed (EGL 0x{egl_error():x})")

    display = get_display(None)
    require(display, "eglGetDisplay")
    require(initialize(display, None, None), "eglInitialize")
    context = surface = None
    try:
        # Pbuffer, GLES 2, RGBA8 and EGL_NONE.
        attributes = (integer * 13)(0x3033, 1, 0x3040, 4, 0x3024, 8,
                                    0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = handle(), integer()
        require(choose_config(display, attributes, c.byref(config), 1, c.byref(count))
                and count.value, "eglChooseConfig")
        require(bind_api(0x30A0), "eglBindAPI(GLES)")
        context = create_context(display, config, None, (integer * 3)(0x3098, 2, 0x3038))
        require(context, "eglCreateContext")
        surface = create_pbuffer(display, config, (integer * 5)(0x3057, 4, 0x3056, 4, 0x3038))
        require(surface, "eglCreatePbufferSurface")
        require(make_current(display, surface, surface, context), "eglMakeCurrent")

        renderer = (get_string(0x1F01) or b"").decode("utf-8", "replace")
        print(f"LYRA_GPU: guest EGL renderer={renderer}", flush=True)
        if not re.match(r"^virgl(?:[ (]|$)", renderer, re.IGNORECASE):
            raise RuntimeError("Mesa did not select virpipe")
        pixel = (c.c_ubyte * 4)()
        clear_color(0.0, 1.0, 0.0, 1.0)
        clear(0x4000)  # GL_COLOR_BUFFER_BIT
        read_pixels(1, 1, 1, 1, 0x1908, 0x1401, pixel)  # GL_RGBA, GL_UNSIGNED_BYTE
        error = gl_error()
        if error or tuple(pixel) != (0, 255, 0, 255):
            raise RuntimeError(f"pbuffer pixel check failed (GL 0x{error:x}, pixel={tuple(pixel)})")
        print("LYRA_GPU: guest EGL pbuffer pixel check passed", flush=True)
    finally:
        make_current(display, None, None, None)
        if surface:
            destroy_surface(display, surface)
        if context:
            destroy_context(display, context)
        terminate(display)


if __name__ == "__main__":
    try:
        probe()
    except (OSError, RuntimeError) as error:
        sys.exit(f"LYRA_GPU: guest EGL probe: {error}")
