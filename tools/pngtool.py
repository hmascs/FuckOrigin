#!/usr/bin/env python3
"""纯 Python PNG 读/写 + 合成（无 Pillow 依赖）：白底转透明、圆角底、缩放拼贴。"""
import zlib, struct, sys

def read_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', "not png"
    pos = 8; idat = b''; w = h = bd = ct = None; plte = None; trns = None
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos+4])[0]; typ = d[pos+4:pos+8]; data = d[pos+8:pos+8+ln]
        if typ == b'IHDR':
            w, h, bd, ct, comp, filt, inter = struct.unpack('>IIBBBBB', data)
            assert bd == 8 and inter == 0, f"unsupported bd={bd} interlace={inter}"
        elif typ == b'PLTE': plte = data
        elif typ == b'tRNS': trns = data
        elif typ == b'IDAT': idat += data
        elif typ == b'IEND': break
        pos += 12 + ln
    raw = zlib.decompress(idat)
    ch = {0:1, 2:3, 3:1, 4:2, 6:4}[ct]
    stride = w * ch
    out = bytearray(h * stride); prev = bytearray(stride); p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p+stride]); p += stride
        if f == 1:
            for i in range(ch, stride): line[i] = (line[i] + line[i-ch]) & 0xFF
        elif f == 2:
            for i in range(stride): line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i-ch] if i >= ch else 0
                b = prev[i]; c = prev[i-ch] if i >= ch else 0
                pp = a + b - c; pa, pb, pc = abs(pp-a), abs(pp-b), abs(pp-c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y*stride:(y+1)*stride] = line
        prev = line
    # 统一成 RGBA
    rgba = bytearray(w*h*4)
    for y in range(h):
        for x in range(w):
            i = y*stride + x*ch
            if ct == 6: r,g,b,a = out[i],out[i+1],out[i+2],out[i+3]
            elif ct == 2: r,g,b,a = out[i],out[i+1],out[i+2],255
            elif ct == 0: r=g=b=out[i]; a=255
            elif ct == 4: r=g=b=out[i]; a=out[i+1]
            else:  # palette
                idx = out[i]; r,g,b = plte[idx*3],plte[idx*3+1],plte[idx*3+2]
                a = trns[idx] if (trns and idx < len(trns)) else 255
            o = (y*w+x)*4; rgba[o]=r; rgba[o+1]=g; rgba[o+2]=b; rgba[o+3]=a
    return w, h, rgba

def write_png(path, w, h, rgba):
    raw = bytearray()
    stride = w*4
    for y in range(h):
        raw.append(0); raw += rgba[y*stride:(y+1)*stride]
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)

def white_to_alpha(w, h, rgba, thr=238):
    for i in range(0, len(rgba), 4):
        r,g,b,a = rgba[i],rgba[i+1],rgba[i+2],rgba[i+3]
        if a > 0 and r > thr and g > thr and b > thr:
            rgba[i+3] = 0

def bbox(w, h, rgba, alpha_min=8):
    x0,y0,x1,y1 = w,h,-1,-1
    for y in range(h):
        for x in range(w):
            if rgba[(y*w+x)*4+3] > alpha_min:
                if x<x0: x0=x
                if y<y0: y0=y
                if x>x1: x1=x
                if y>y1: y1=y
    return x0,y0,x1+1,y1+1

def resize(w,h,rgba,nw,nh):
    out = bytearray(nw*nh*4)
    for y in range(nh):
        sy = min(h-1, int(y*h/nh))
        for x in range(nw):
            sx = min(w-1, int(x*w/nw))
            si=(sy*w+sx)*4; di=(y*nw+x)*4
            out[di:di+4]=rgba[si:si+4]
    return out

def blend(dst_w,dst_h,dst, src_w,src_h,src, ox, oy):
    for y in range(src_h):
        ty = oy+y
        if ty < 0 or ty >= dst_h: continue
        for x in range(src_w):
            tx = ox+x
            if tx < 0 or tx >= dst_w: continue
            si=(y*src_w+x)*4; a=src[si+3]
            if a == 0: continue
            di=(ty*dst_w+tx)*4
            if a == 255:
                dst[di:di+4]=src[si:si+4]
            else:
                inv=255-a; al=a/255.0
                for k in range(3):
                    dst[di+k]=int(src[si+k]*al + dst[di+k]*(1-al))
                dst[di+3]=min(255, int(a + dst[di+3]*(1-al)))

def rounded_bg(w,h,rgba,color, radius_ratio=0.24):
    r = int(min(w,h)*radius_ratio)
    for y in range(h):
        for x in range(w):
            dx = 0; dy = 0
            if x < r: dx = r-x
            elif x > w-1-r: dx = x-(w-1-r)
            if y < r: dy = r-y
            elif y > h-1-r: dy = y-(h-1-r)
            if dx*dx + dy*dy <= r*r:
                i=(y*w+x)*4
                rgba[i],rgba[i+1],rgba[i+2],rgba[i+3]=color[0],color[1],color[2],255

if __name__ == '__main__':
    cmd = sys.argv[1]
    if cmd == 'icon':
        src, out, size = sys.argv[2], sys.argv[3], int(sys.argv[4])
        w,h,px = read_png(src)
        white_to_alpha(w,h,px)
        x0,y0,x1,y1 = bbox(w,h,px)
        cw,chh = x1-x0, y1-y0
        cropped = bytearray(cw*chh*4)
        for y in range(chh):
            si=((y+y0)*w+x0)*4; cropped[y*cw*4:(y+1)*cw*4]=px[si:si+cw*4]
        scale = int(size*0.70)
        sw, sh = (scale, int(scale*chh/cw)) if cw>=chh else (int(scale*cw/chh), scale)
        small = resize(cw,chh,cropped,sw,sh)
        canvas = bytearray(size*size*4)
        rounded_bg(size,size,canvas,(0x12,0x14,0x1A))
        blend(size,size,canvas,sw,sh,small,(size-sw)//2,(size-sh)//2)
        write_png(out,size,size,canvas)
        print("wrote", out, size)
    elif cmd == 'iconw':
        src, out, size = sys.argv[2], sys.argv[3], int(sys.argv[4])
        w,h,px = read_png(src)
        white_to_alpha(w,h,px)
        x0,y0,x1,y1 = bbox(w,h,px)
        cw,chh = x1-x0, y1-y0
        cropped = bytearray(cw*chh*4)
        for y in range(chh):
            si=((y+y0)*w+x0)*4; cropped[y*cw*4:(y+1)*cw*4]=px[si:si+cw*4]
        scale = int(size*0.74)
        sw, sh = (scale, int(scale*chh/cw)) if cw>=chh else (int(scale*cw/chh), scale)
        small = resize(cw,chh,cropped,sw,sh)
        canvas = bytearray(size*size*4)
        rounded_bg(size,size,canvas,(0xFF,0xFF,0xFF),0.24)
        blend(size,size,canvas,sw,sh,small,(size-sw)//2,(size-sh)//2)
        write_png(out,size,size,canvas); print("wrote", out, size)
    elif cmd == 'fg':
        src, out, size = sys.argv[2], sys.argv[3], int(sys.argv[4])
        w,h,px = read_png(src); white_to_alpha(w,h,px)
        x0,y0,x1,y1 = bbox(w,h,px)
        cw,chh = x1-x0,y1-y0
        cropped = bytearray(cw*chh*4)
        for y in range(chh):
            si=((y+y0)*w+x0)*4; cropped[y*cw*4:(y+1)*cw*4]=px[si:si+cw*4]
        scale = int(size*0.52)
        sw, sh = (scale, int(scale*chh/cw)) if cw>=chh else (int(scale*cw/chh), scale)
        small = resize(cw,chh,cropped,sw,sh)
        canvas = bytearray(size*size*4)
        blend(size,size,canvas,sw,sh,small,(size-sw)//2,(size-sh)//2)
        write_png(out,size,size,canvas); print("wrote", out, size)
