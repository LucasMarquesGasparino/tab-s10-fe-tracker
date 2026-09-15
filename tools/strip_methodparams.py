#!/data/data/com.termux/files/usr/bin/python3
import struct, sys, pathlib

def strip_file(path):
    data = open(path, "rb").read()
    if len(data) < 10 or data[0:4] != b'\xCA\xFE\xBA\xBE':
        return False
    p = 0
    out = bytearray()
    # copy header magic, minor, major, cp_count
    magic = data[p:p+4]; out.extend(magic); p+=4
    minor = data[p:p+2]; out.extend(minor); p+=2
    major = data[p:p+2]; out.extend(major); p+=2
    cp_count_bytes = data[p:p+2]; out.extend(cp_count_bytes); p+=2
    cp_count = struct.unpack(">H", cp_count_bytes)[0]
    # we need to track utf8 strings by index
    utf8_map = {}
    # copy cp entries
    i = 1
    # we already wrote cp_count, now copy each entry bytes
    # we need to copy exactly as original, so we just read tag, then length, and append to out
    while i < cp_count:
        tag = data[p]
        out.append(tag)
        p+=1
        if tag == 1: # utf8
            length = struct.unpack(">H", data[p:p+2])[0]
            out.extend(data[p:p+2])
            p+=2
            bytes_str = data[p:p+length]
            out.extend(bytes_str)
            try:
                utf8_map[i] = bytes_str.decode('utf-8', errors='ignore')
            except:
                utf8_map[i] = ""
            p+=length
        elif tag in (3,4):
            out.extend(data[p:p+4]); p+=4
        elif tag in (5,6):
            out.extend(data[p:p+8]); p+=8
            i+=1 # takes two slots
            # second slot is unused, no data
        elif tag == 7:
            out.extend(data[p:p+2]); p+=2
        elif tag == 8:
            out.extend(data[p:p+2]); p+=2
        elif tag in (9,10,11):
            out.extend(data[p:p+4]); p+=4
        elif tag == 12:
            out.extend(data[p:p+4]); p+=4
        elif tag == 15:
            out.extend(data[p:p+3]); p+=3
        elif tag == 16:
            out.extend(data[p:p+2]); p+=2
        elif tag == 18:
            out.extend(data[p:p+4]); p+=4
        elif tag == 19: # Module Java9
            out.extend(data[p:p+2]); p+=2
        elif tag == 20: # Package Java9
            out.extend(data[p:p+2]); p+=2
        else:
            raise ValueError(f"unknown tag {tag} at cp index {i}")
        i+=1

    # Now p points to access_flags
    # Copy up to fields_count, but we need to handle fields, methods, class attrs with possible stripping
    # Helper to copy attributes with stripping
    def copy_attrs(count_bytes_offset, count):
        # This helper is not needed if we handle inline
        pass

    # access_flags, this, super, interfaces
    # copy access_flags
    out.extend(data[p:p+2]); p+=2
    out.extend(data[p:p+2]); p+=2 # this
    out.extend(data[p:p+2]); p+=2 # super
    intf_count = struct.unpack(">H", data[p:p+2])[0]
    out.extend(data[p:p+2]); p+=2
    out.extend(data[p:p+2*intf_count]); p+=2*intf_count

    # fields
    fields_count = struct.unpack(">H", data[p:p+2])[0]
    out.extend(data[p:p+2]); p+=2
    for _ in range(fields_count):
        # field info: access, name, desc, attr_count
        field_header = data[p:p+6]
        # we will need to handle attrs, so parse
        acc = struct.unpack(">H", data[p:p+2])[0]
        name_idx = struct.unpack(">H", data[p+2:p+4])[0]
        desc_idx = struct.unpack(">H", data[p+4:p+6])[0]
        attr_count = struct.unpack(">H", data[p+6:p+8])[0]
        p+=8
        # collect attrs to keep
        kept_attrs = []
        for __ in range(attr_count):
            an = struct.unpack(">H", data[p:p+2])[0]
            al = struct.unpack(">I", data[p+2:p+6])[0]
            attr_bytes = data[p:p+6+al]
            # check if should strip
            name_str = utf8_map.get(an, "")
            if name_str == "MethodParameters":
                # skip
                pass
            else:
                kept_attrs.append(attr_bytes)
            p+=6+al
        # write field header with new count
        out.extend(struct.pack(">H", acc))
        out.extend(struct.pack(">H", name_idx))
        out.extend(struct.pack(">H", desc_idx))
        out.extend(struct.pack(">H", len(kept_attrs)))
        for ab in kept_attrs:
            out.extend(ab)

    # methods
    methods_count = struct.unpack(">H", data[p:p+2])[0]
    out.extend(data[p:p+2]); p+=2
    for _ in range(methods_count):
        acc = struct.unpack(">H", data[p:p+2])[0]
        name_idx = struct.unpack(">H", data[p+2:p+4])[0]
        desc_idx = struct.unpack(">H", data[p+4:p+6])[0]
        attr_count = struct.unpack(">H", data[p+6:p+8])[0]
        p+=8
        kept = []
        for __ in range(attr_count):
            an = struct.unpack(">H", data[p:p+2])[0]
            al = struct.unpack(">I", data[p+2:p+6])[0]
            attr_bytes = data[p:p+6+al]
            name_str = utf8_map.get(an, "")
            if name_str == "MethodParameters":
                pass
            else:
                kept.append(attr_bytes)
            p+=6+al
        out.extend(struct.pack(">H", acc))
        out.extend(struct.pack(">H", name_idx))
        out.extend(struct.pack(">H", desc_idx))
        out.extend(struct.pack(">H", len(kept)))
        for ab in kept:
            out.extend(ab)

    # class attributes
    class_attr_count = struct.unpack(">H", data[p:p+2])[0]
    # we need to parse them similarly
    p+=2
    kept_class = []
    for _ in range(class_attr_count):
        an = struct.unpack(">H", data[p:p+2])[0]
        al = struct.unpack(">I", data[p+2:p+6])[0]
        attr_bytes = data[p:p+6+al]
        name_str = utf8_map.get(an, "")
        if name_str == "MethodParameters":
            pass
        else:
            kept_class.append(attr_bytes)
        p+=6+al
    out.extend(struct.pack(">H", len(kept_class)))
    for ab in kept_class:
        out.extend(ab)

    # Should have consumed all
    if p != len(data):
        # copy trailing? but should be exact
        # print warning
        # print(f"warning leftover {len(data)-p}")
        out.extend(data[p:])

    # only write if changed
    if len(out) != len(data) or out != data:
        open(path, "wb").write(out)
        return True
    return False

if __name__ == "__main__":
    import sys
    base = pathlib.Path(sys.argv[1]) if len(sys.argv)>1 else pathlib.Path(".")
    count=0
    stripped=0
    for cls in base.rglob("*.class"):
        count+=1
        if strip_file(str(cls)):
            stripped+=1
            print(f"stripped {cls}")
    print(f"processed {count}, stripped {stripped}")
