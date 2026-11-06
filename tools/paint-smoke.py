"""Exercise real X11 drawing through the same DISPLAY/XAUTHORITY provided to desktop apps."""
import ctypes
import ctypes.util
import time

x11 = ctypes.CDLL(ctypes.util.find_library("X11"))
x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
x11.XOpenDisplay.restype = ctypes.c_void_p
x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
x11.XDefaultRootWindow.restype = ctypes.c_ulong
x11.XSetWindowBackground.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_ulong]
x11.XClearWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
x11.XFlush.argtypes = [ctypes.c_void_p]
x11.XCloseDisplay.argtypes = [ctypes.c_void_p]
display = x11.XOpenDisplay(None)
if not display:
    raise RuntimeError("Cannot open the authenticated X display")
try:
    root = x11.XDefaultRootWindow(display)
    for index in range(80):
        x11.XSetWindowBackground(display, root, 0x225588 if index % 2 == 0 else 0x884422)
        x11.XClearWindow(display, root)
        x11.XFlush(display)
        time.sleep(0.2)
finally:
    x11.XCloseDisplay(display)
