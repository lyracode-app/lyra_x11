"""Draw and read back pixels with guest VirGL or desktop Zink."""

import ctypes as c
import re
import sys


def bind(library, name, result, *parameters):
    function = getattr(library, name)
    function.restype = result
    function.argtypes = parameters
    return function


def draw_shader(gl, version):
    uint, integer, handle = c.c_uint, c.c_int, c.c_void_p
    create_shader = bind(gl, "glCreateShader", uint, uint)
    shader_source = bind(gl, "glShaderSource", None, uint, integer, c.POINTER(c.c_char_p), handle)
    compile_shader = bind(gl, "glCompileShader", None, uint)
    shader_status = bind(gl, "glGetShaderiv", None, uint, uint, c.POINTER(integer))
    shader_log = bind(gl, "glGetShaderInfoLog", None, uint, integer, handle, handle)
    create_program = bind(gl, "glCreateProgram", uint)
    attach_shader = bind(gl, "glAttachShader", None, uint, uint)
    link_program = bind(gl, "glLinkProgram", None, uint)
    program_status = bind(gl, "glGetProgramiv", None, uint, uint, c.POINTER(integer))
    program_log = bind(gl, "glGetProgramInfoLog", None, uint, integer, handle, handle)
    use_program = bind(gl, "glUseProgram", None, uint)
    gen_vao = bind(gl, "glGenVertexArrays", None, integer, c.POINTER(uint))
    bind_vao = bind(gl, "glBindVertexArray", None, uint)
    draw = bind(gl, "glDrawArrays", None, uint, integer, integer)
    viewport = bind(gl, "glViewport", None, integer, integer, integer, integer)
    vertex = """
        const vec2 positions[3] = vec2[3](vec2(-1,-1), vec2(3,-1), vec2(-1,3));
        void main() { gl_Position = vec4(positions[gl_VertexID], 0, 1); }
    """
    fragment = """
        layout(location=0) out vec4 pixel;
        void main() { pixel = vec4(0, 1, 0, 1); }
    """
    program, status = create_program(), integer()
    log = c.create_string_buffer(1024)
    for stage, source in ((0x8B31, vertex), (0x8B30, fragment)):
        shader = create_shader(stage)
        code = c.c_char_p(f"#version {version} core\n{source}".encode())
        shader_source(shader, 1, c.byref(code), None)
        compile_shader(shader)
        shader_status(shader, 0x8B81, c.byref(status))  # GL_COMPILE_STATUS
        if not status.value:
            shader_log(shader, len(log), None, log)
            raise RuntimeError(f"GLSL {version} compile failed: {log.value.decode(errors='replace')}")
        attach_shader(program, shader)
    link_program(program)
    program_status(program, 0x8B82, c.byref(status))  # GL_LINK_STATUS
    if not status.value:
        program_log(program, len(log), None, log)
        raise RuntimeError(f"GLSL {version} link failed: {log.value.decode(errors='replace')}")
    vao = uint()
    gen_vao(1, c.byref(vao))
    bind_vao(vao)
    use_program(program)
    viewport(0, 0, 4, 4)
    draw(4, 0, 3)  # GL_TRIANGLES


def probe(zink=False, virgl_angle=False):
    desktop = zink or virgl_angle
    egl = c.CDLL("libEGL.so.1")
    gl = c.CDLL("libGL.so.1" if desktop else "libGLESv2.so.2")
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
        # Pbuffer, desktop OpenGL or GLES 2, RGBA8 and EGL_NONE.
        renderable = 8 if desktop else 4
        attributes = (integer * 13)(0x3033, 1, 0x3040, renderable, 0x3024, 8,
                                    0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3038)
        config, count = handle(), integer()
        require(choose_config(display, attributes, c.byref(config), 1, c.byref(count))
                and count.value, "eglChooseConfig")
        require(bind_api(0x30A2 if desktop else 0x30A0), "eglBindAPI")
        if desktop:
            # Mesa may promote the requested core context. Query its real version below.
            core = (integer * 7)(0x3098, 3, 0x30FB, 3, 0x30FD, 1, 0x3038)
            context = create_context(display, config, None, core)
        else:
            context = create_context(display, config, None, (integer * 3)(0x3098, 2, 0x3038))
        require(context, "eglCreateContext")
        surface = create_pbuffer(display, config, (integer * 5)(0x3057, 4, 0x3056, 4, 0x3038))
        require(surface, "eglCreatePbufferSurface")
        require(make_current(display, surface, surface, context), "eglMakeCurrent")

        renderer = (get_string(0x1F01) or b"").decode("utf-8", "replace")
        version = (get_string(0x1F02) or b"").decode("utf-8", "replace")
        print(f"LYRA_GPU: guest EGL renderer={renderer}; version={version}", flush=True)
        expected = "zink" if zink else "virgl"
        if not re.match(rf"^{expected}(?:[ (]|$)", renderer, re.IGNORECASE):
            raise RuntimeError(f"Mesa did not select {expected}")
        if virgl_angle and "ANGLE (ARM, Vulkan " not in renderer:
            raise RuntimeError("VirGL did not select ANGLE Vulkan")
        if zink and re.search(r"llvmpipe|lavapipe|swiftshader|softpipe|software rasterizer", renderer, re.IGNORECASE):
            raise RuntimeError("Zink selected a software Vulkan device")
        pixel = (c.c_ubyte * 4)()
        clear_color(0.0, float(not desktop), 0.0, 1.0)
        clear(0x4000)  # GL_COLOR_BUFFER_BIT
        if desktop:
            gl_version = re.match(r"^(\d+)\.(\d+)", version)
            if not gl_version:
                raise RuntimeError("Could not read the desktop OpenGL version")
            major, minor = map(int, gl_version.groups())
            if (major, minor) < (3, 3):
                raise RuntimeError(f"OpenGL 3.3 core is required, detected {version}")
            draw_shader(gl, 330 if virgl_angle else major * 100 + minor * 10)
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
        probe(zink="--zink" in sys.argv[1:], virgl_angle="--virgl-angle" in sys.argv[1:])
    except (OSError, RuntimeError) as error:
        sys.exit(f"LYRA_GPU: guest EGL probe: {error}")
