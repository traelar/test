import json
import math
import os
import random
import sys
from pathlib import Path

import bpy
from mathutils import Vector


ASSET_NAME = "industrial_scrap_shredder_v23"
OUT = Path(os.environ.get("SHREDDER_OUT", Path.cwd() / "out")).resolve()
XML_OUT = OUT / "xml"
PREVIEW_OUT = OUT / "previews"
SOURCE_OUT = OUT / "source"
TEXTURE_OUT = OUT / "textures"
for folder in (XML_OUT, PREVIEW_OUT, SOURCE_OUT, TEXTURE_OUT):
    folder.mkdir(parents=True, exist_ok=True)

SOLLUMZ_PATH = Path(os.environ["SOLLUMZ_PATH"]).resolve()
sys.path.insert(0, str(SOLLUMZ_PATH.parent))
bpy.ops.preferences.addon_enable(module="Sollumz")
import Sollumz

from Sollumz.sollumz_properties import LODLevel, SollumType
from Sollumz.tools.boundhelper import convert_obj_to_bvh
from Sollumz.tools.drawablehelper import MaterialConverter, convert_obj_to_drawable
from Sollumz.tools.ytyphelper import ytyp_from_objects
from Sollumz.ybn.collision_materials import create_collision_material_from_index
from Sollumz.ydr.ydrexport import export_ydr


random.seed(271828)
bpy.ops.object.select_all(action="SELECT")
bpy.ops.object.delete(use_global=False)
for datablocks in (bpy.data.meshes, bpy.data.curves, bpy.data.materials, bpy.data.cameras, bpy.data.lights):
    pass

render_parts = []
rotor_parts = [[], []]
input_slat_parts = []
output_slat_parts = []
scrap_chunk_parts = []
material_cache = {}


def clamp(v):
    return max(0.0, min(1.0, v))


def make_texture(name, color, rust=0.0, grime=0.0, scratches=0.0, size=512):
    image = bpy.data.images.new(name, width=size, height=size, alpha=True)
    pixels = [0.0] * (size * size * 4)
    cr, cg, cb = color
    for y in range(size):
        for x in range(size):
            i = (y * size + x) * 4
            seed = ((x * 73856093) ^ (y * 19349663) ^ (len(name) * 83492791)) & 0xFFFFFFFF
            fine = ((seed >> 8) & 255) / 255.0
            broad = 0.5 + 0.5 * math.sin(x * 0.071 + math.sin(y * 0.037) * 3.0)
            variation = (fine - 0.5) * 0.08 + (broad - 0.5) * 0.05
            r, g, b = cr + variation, cg + variation, cb + variation
            rust_mask = ((seed & 1023) / 1023.0) < rust * (0.25 + 0.75 * broad)
            if rust_mask:
                blend = 0.35 + 0.45 * ((seed >> 12) & 255) / 255.0
                r = r * (1.0 - blend) + 0.34 * blend
                g = g * (1.0 - blend) + 0.105 * blend
                b = b * (1.0 - blend) + 0.025 * blend
            grime_mask = (((seed >> 3) & 2047) / 2047.0) < grime * (0.4 + 0.6 * (y / size))
            if grime_mask:
                r *= 0.55
                g *= 0.57
                b *= 0.54
            if scratches and ((x * 17 + y * 3 + seed) % max(11, int(70 / scratches))) == 0:
                r = min(1.0, r + 0.16)
                g = min(1.0, g + 0.14)
                b = min(1.0, b + 0.10)
            pixels[i:i + 4] = (clamp(r), clamp(g), clamp(b), 1.0)
    image.pixels.foreach_set(pixels)
    # Flush generated pixels before Cycles or the Sollumz texture exporter samples
    # the image. Without this, Blender's background renderer can retain the
    # newly-created image's black GPU buffer even though the saved PNG is valid.
    image.update()
    image.file_format = "PNG"
    image.filepath_raw = str(TEXTURE_OUT / f"{name}.png")
    image.save()
    return image


def make_material(name, color, metallic=0.0, roughness=0.55, rust=0.0, grime=0.0, scratches=0.0):
    if name in material_cache:
        return material_cache[name]
    image = make_texture(name, color, rust, grime, scratches)
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    nodes.clear()
    out = nodes.new("ShaderNodeOutputMaterial")
    bsdf = nodes.new("ShaderNodeBsdfPrincipled")
    tex = nodes.new("ShaderNodeTexImage")
    tex.name = "DiffuseTexture"
    tex.image = image
    tex.interpolation = "Linear"
    bsdf.inputs["Metallic"].default_value = metallic
    bsdf.inputs["Roughness"].default_value = roughness
    mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
    mat.node_tree.links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
    material_cache[name] = mat
    return mat


MAT_BLUE = make_material("shredder_blue", (0.040, 0.145, 0.205), 0.34, 0.56, 0.042, 0.070, 0.14)
MAT_YELLOW = make_material("safety_yellow", (0.72, 0.405, 0.030), 0.12, 0.58, 0.032, 0.060, 0.10)
MAT_STEEL = make_material("cutter_steel", (0.245, 0.275, 0.300), 0.72, 0.34, 0.045, 0.085, 0.20)
MAT_DARK = make_material("machine_dark", (0.032, 0.041, 0.046), 0.42, 0.50, 0.020, 0.090, 0.07)
MAT_RUBBER = make_material("belt_rubber", (0.025, 0.031, 0.034), 0.04, 0.78, 0.0, 0.018, 0.0)
MAT_RED = make_material("emergency_red", (0.62, 0.018, 0.012), 0.10, 0.48, 0.020, 0.020, 0.04)
MAT_SILVER = make_material("brushed_metal", (0.40, 0.445, 0.480), 0.78, 0.34, 0.035, 0.040, 0.16)
MAT_WHITE = make_material("label_white", (0.94, 0.94, 0.88), 0.05, 0.52, 0.006, 0.006, 0.01)
MAT_GREEN = make_material("indicator_green", (0.025, 0.82, 0.14), 0.08, 0.28, 0.0, 0.0, 0.0)


def finish_mesh(obj, mat, bevel=0.03, smooth=False, add=True):
    if mat is not None:
        obj.data.materials.append(mat)
    bpy.context.view_layer.objects.active = obj
    obj.select_set(True)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel > 0.0:
        mod = obj.modifiers.new("Edge softening", "BEVEL")
        mod.width = bevel
        mod.segments = 2
        mod.limit_method = "ANGLE"
        bpy.ops.object.modifier_apply(modifier=mod.name)
    if smooth:
        for polygon in obj.data.polygons:
            polygon.use_smooth = True
    obj.select_set(False)
    if add:
        render_parts.append(obj)
    return obj


def box(name, location, dimensions, mat=MAT_BLUE, rotation=(0.0, 0.0, 0.0), bevel=0.03, add=True):
    bpy.ops.mesh.primitive_cube_add(location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = dimensions
    return finish_mesh(obj, mat, bevel=bevel, add=add)


def cylinder(name, location, radius, depth, mat=MAT_STEEL, rotation=(0.0, 0.0, 0.0), vertices=16, bevel=0.018, add=True):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=depth, location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    return finish_mesh(obj, mat, bevel=bevel, smooth=True, add=add)


def pipe(name, start, end, radius=0.035, mat=MAT_YELLOW, vertices=8, add=True):
    a = Vector(start)
    b = Vector(end)
    direction = b - a
    middle = (a + b) * 0.5
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices, radius=radius, depth=direction.length, location=middle)
    obj = bpy.context.object
    obj.name = name
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = direction.to_track_quat("Z", "Y")
    return finish_mesh(obj, mat, bevel=0.012, smooth=True, add=add)


def beam(name, start, end, width=0.10, depth=0.10, mat=MAT_DARK, bevel=0.012, add=True):
    a = Vector(start)
    b = Vector(end)
    direction = b - a
    bpy.ops.mesh.primitive_cube_add(location=(a + b) * 0.5)
    obj = bpy.context.object
    obj.name = name
    obj.dimensions = (width, depth, direction.length)
    obj.rotation_mode = "QUATERNION"
    obj.rotation_quaternion = direction.to_track_quat("Z", "Y")
    return finish_mesh(obj, mat, bevel=bevel, add=add)


def prism(name, vertices, faces, mat, bevel=0.02, add=True):
    mesh = bpy.data.meshes.new(f"{name}_mesh")
    mesh.from_pydata(vertices, [], faces)
    mesh.update()
    obj = bpy.data.objects.new(name, mesh)
    bpy.context.collection.objects.link(obj)
    return finish_mesh(obj, mat, bevel=bevel, add=add)


def sloped_wall(name, side):
    z0, z1 = 2.76, 4.08
    lx0, lx1 = 1.20, 1.72
    ly0, ly1 = 1.08, 1.48
    t = 0.10
    if side in ("front", "back"):
        sign = -1 if side == "front" else 1
        y0, y1 = sign * ly0, sign * ly1
        verts = [
            (-lx0, y0, z0), (lx0, y0, z0), (lx1, y1, z1), (-lx1, y1, z1),
            (-lx0, y0 + sign*t, z0), (lx0, y0 + sign*t, z0), (lx1, y1 + sign*t, z1), (-lx1, y1 + sign*t, z1),
        ]
    else:
        sign = -1 if side == "left" else 1
        x0, x1 = sign * lx0, sign * lx1
        verts = [
            (x0, -ly0, z0), (x0, ly0, z0), (x1, ly1, z1), (x1, -ly1, z1),
            (x0 + sign*t, -ly0, z0), (x0 + sign*t, ly0, z0), (x1 + sign*t, ly1, z1), (x1 + sign*t, -ly1, z1),
        ]
    faces = [(0,1,2,3), (4,7,6,5), (0,4,5,1), (1,5,6,2), (2,6,7,3), (3,7,4,0)]
    return prism(f"hopper_{side}", verts, faces, MAT_BLUE, bevel=0.025)


def add_text(name, body, location, size, mat, rotation=(math.radians(90), 0.0, 0.0), extrude=0.009):
    bpy.ops.object.text_add(location=location, rotation=rotation)
    obj = bpy.context.object
    obj.name = name
    obj.data.body = body
    obj.data.align_x = "CENTER"
    obj.data.align_y = "CENTER"
    obj.data.size = size
    font_path = Path("C:/Windows/Fonts/arialbd.ttf")
    if font_path.exists():
        obj.data.font = bpy.data.fonts.load(str(font_path))
    obj.data.extrude = min(extrude, 0.005)
    obj.data.bevel_depth = 0.0008
    obj.data.materials.append(mat)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.convert(target="MESH")
    render_parts.append(obj)
    return obj


# Heavy base skid and cross members.
for y in (-1.18, 1.18):
    box("base_skid", (0.0, y, 0.20), (6.25, 0.22, 0.28), MAT_DARK, bevel=0.045)
for x in (-2.55, -1.05, 0.55, 2.40):
    box("base_crossmember", (x, 0.0, 0.23), (0.22, 2.55, 0.24), MAT_DARK, bevel=0.035)
for x in (-2.70, -0.85, 0.85, 2.70):
    for y in (-1.23, 1.23):
        box("foot_pad", (x, y, 0.06), (0.50, 0.50, 0.12), MAT_STEEL, bevel=0.035)

# Four load-bearing pedestals transfer the chamber into the base skids. These
# close the previous daylight gap that made the complete machine appear to
# float above its foundation.
for x in (-1.05, 1.05):
    for y in (-1.05, 1.05):
        box("machine_pedestal", (x, y, 0.75), (0.24, 0.24, 0.86),
            MAT_DARK, bevel=0.018)
for y in (-1.05, 1.05):
    box("machine_support_saddle", (0.0, y, 1.12), (2.48, 0.28, 0.22),
        MAT_DARK, bevel=0.020)
for x in (-1.05, 1.05):
    for y in (-1.05, 1.05):
        beam("machine_pedestal_gusset", (x, y, 0.38),
             (x * 0.82, y, 1.08), width=0.10, depth=0.12,
             mat=MAT_DARK, bevel=0.010)

# Central machine chamber and reinforced corner columns.
box("shredder_chamber", (0.0, 0.0, 2.18), (2.82, 2.55, 1.15), MAT_BLUE, bevel=0.085)
box("chamber_lower", (0.0, 0.0, 1.42), (2.60, 2.38, 0.48), MAT_DARK, bevel=0.045)
for x in (-1.35, 1.35):
    for y in (-1.20, 1.20):
        box("reinforced_column", (x, y, 2.30), (0.20, 0.20, 1.55), MAT_YELLOW, bevel=0.035)
for side in ("front", "back", "left", "right"):
    sloped_wall(side, side)

# Top rim around the feed hopper.
for y in (-1.53, 1.53):
    box("hopper_top_rim", (0.0, y, 4.10), (3.62, 0.15, 0.16), MAT_YELLOW, bevel=0.035)
for x in (-1.77, 1.77):
    box("hopper_top_rim", (x, 0.0, 4.10), (0.15, 3.20, 0.16), MAT_YELLOW, bevel=0.035)
for x in (-1.77, 1.77):
    for y in (-1.53, 1.53):
        box("hopper_corner_cap", (x, y, 4.105), (0.22, 0.22, 0.17),
            MAT_YELLOW, bevel=0.026)

# Structural corner channels cover the four wall seams and eliminate daylight
# gaps where the separately formed hopper panels meet.
for sx in (-1, 1):
    for sy in (-1, 1):
        beam(
            "hopper_corner_channel",
            (sx * 1.205, sy * 1.085, 2.75),
            (sx * 1.735, sy * 1.495, 4.12),
            width=0.13,
            depth=0.13,
            mat=MAT_DARK,
            bevel=0.016,
        )

# Twin shafts, cutter discs and interlocking teeth.
for shaft_idx, x in enumerate((-0.39, 0.39)):
    rotor_parts[shaft_idx].append(
        cylinder("main_shaft", (x, 0.0, 2.44), 0.16, 2.30, MAT_STEEL,
                 rotation=(math.radians(90), 0, 0), vertices=16)
    )
    for disc_idx in range(11):
        y = -1.02 + disc_idx * 0.204
        phase = (disc_idx % 2) * math.radians(30) + shaft_idx * math.radians(30)
        rotor_parts[shaft_idx].append(
            cylinder("cutter_disc", (x, y, 2.44), 0.39, 0.13, MAT_STEEL,
                     rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.014)
        )
        for tooth_idx in range(6):
            angle = phase + tooth_idx * math.tau / 6.0
            tx = x + math.cos(angle) * 0.36
            tz = 2.44 + math.sin(angle) * 0.36
            rotor_parts[shaft_idx].append(
                box("cutter_tooth", (tx, y, tz), (0.24, 0.145, 0.13), MAT_SILVER,
                    rotation=(0.0, -angle, 0.0), bevel=0.014)
            )

# Fixed guards hide the cutter envelope from both exterior chamber faces while
# leaving the interlocking teeth visible from the open hopper above.
for y in (-1.20, 1.20):
    box("cutter_throat_guard", (0.0, y, 2.86), (2.58, 0.16, 0.46),
        MAT_DARK, bevel=0.024)

# Side gearboxes, electric motors and hydraulic details.
for x in (-0.62, 0.62):
    box("gearbox", (x, 1.53, 2.34), (0.86, 0.50, 0.82), MAT_YELLOW, bevel=0.095)
    cylinder("gearbox_cover", (x, 1.80, 2.34), 0.34, 0.08, MAT_DARK,
             rotation=(math.radians(90), 0, 0), vertices=20, bevel=0.015)
    cylinder("electric_motor", (x, 1.74, 1.33), 0.31, 0.90, MAT_BLUE,
             rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.025)
    cylinder("motor_fan", (x, 2.21, 1.33), 0.25, 0.10, MAT_DARK,
             rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.012)
    for fin in range(-3, 4):
        box("motor_cooling_fin", (x + fin * 0.075, 1.75, 1.33), (0.026, 0.78, 0.47), MAT_DARK, bevel=0.006)

for x in (-1.10, 1.10):
    cylinder("hydraulic_ram", (x, -1.43, 1.66), 0.075, 0.75, MAT_RED,
             rotation=(math.radians(90), 0, 0), vertices=12, bevel=0.012)
    cylinder("hydraulic_rod", (x, -1.86, 1.66), 0.038, 0.25, MAT_SILVER,
             rotation=(math.radians(90), 0, 0), vertices=10, bevel=0.008)

# Long inclined feed conveyor. Its upper roller discharges over the hopper so
# players can place scrap at ground level instead of reaching into the machine.
conv_angle = math.radians(-38.5)
input_center = Vector((-3.95, 0.0, 2.60))
input_length = 6.60

def point_on_input(local_x, z_offset=0.0):
    return Vector((
        input_center.x + math.cos(conv_angle) * local_x,
        0.0,
        input_center.z - math.sin(conv_angle) * local_x + z_offset,
    ))

box("input_belt", input_center, (input_length, 1.86, 0.14), MAT_RUBBER,
    rotation=(0, conv_angle, 0), bevel=0.018)
for y in (-1.02, 1.02):
    box("input_side_rail", (input_center.x, y, input_center.z + 0.22),
        (input_length + 0.12, 0.14, 0.44), MAT_BLUE,
        rotation=(0, conv_angle, 0), bevel=0.022)
for local_x in (-3.08, 3.08):
    roller_pos = point_on_input(local_x)
    cylinder("input_conveyor_roller", roller_pos, 0.17, 1.90, MAT_STEEL,
             rotation=(math.radians(90), 0, 0), vertices=20, bevel=0.012)

# Repeating steel cleats are exported as a separate visual prop. The client
# shifts the complete pattern by one pitch and wraps it for seamless motion.
input_slat_pitch = 0.265
# Leave one complete animation pitch inside each roller. Translating the old
# edge-to-edge pattern pushed its final cleat into open air above the hopper.
for index in range(22):
    local_x = -2.82 + index * input_slat_pitch
    slat_pos = point_on_input(local_x, 0.105)
    input_slat_parts.append(
        box("input_moving_cleat", slat_pos, (0.055, 1.72, 0.045), MAT_STEEL,
            rotation=(0, conv_angle, 0), bevel=0.008)
    )

# Five rigid portal frames, each with feet, ground ties and a top saddle,
# transfer the inclined conveyor into a continuous foundation frame.
INPUT_SUPPORT_LOCAL_X = (-2.85, -1.65, -0.45, 0.75, 1.80)
input_support_points = [point_on_input(local_x, -0.14)
                        for local_x in INPUT_SUPPORT_LOCAL_X]
for y in (-0.88, 0.88):
    rail_start = input_support_points[0].x - 0.30
    rail_end = input_support_points[-1].x + 0.34
    box("input_foundation_rail", ((rail_start + rail_end) * 0.5, y, 0.15),
        (rail_end - rail_start, 0.22, 0.20), MAT_DARK, bevel=0.018)

for support_index, support_point in enumerate(input_support_points):
    for y in (-0.88, 0.88):
        leg_bottom = 0.22
        leg_top = support_point.z - 0.03
        leg_height = max(0.22, leg_top - leg_bottom)
        box("input_support_leg", (support_point.x, y, leg_bottom + leg_height * 0.5),
            (0.14, 0.14, leg_height), MAT_DARK, bevel=0.012)
        box("input_support_foot", (support_point.x, y, 0.055),
            (0.42, 0.38, 0.11), MAT_STEEL, bevel=0.018)
    box("input_support_top_saddle", (support_point.x, 0.0, support_point.z - 0.04),
        (0.18, 1.98, 0.16), MAT_DARK, bevel=0.010)
    box("input_support_ground_tie", (support_point.x, 0.0, 0.24),
        (0.18, 1.86, 0.14), MAT_DARK, bevel=0.010)
    if support_index > 0:
        previous = input_support_points[support_index - 1]
        for y in (-0.88, 0.88):
            beam("input_support_x_brace", (previous.x, y, 0.30),
                 (support_point.x, y, support_point.z - 0.12),
                 width=0.075, depth=0.075, mat=MAT_DARK, bevel=0.008)
            beam("input_support_x_brace", (previous.x, y, previous.z - 0.12),
                 (support_point.x, y, 0.30),
                 width=0.075, depth=0.075, mat=MAT_DARK, bevel=0.008)

# Tie the conveyor foundation into the shredder skid, then clamp the elevated
# discharge end to a reinforced saddle on the hopper rim. This removes the
# unsupported cantilever and makes the conveyor one continuous assembly.
box("input_foundation_machine_tie", (-2.35, 0.0, 0.21),
    (0.30, 2.50, 0.20), MAT_DARK, bevel=0.018)
input_mount_point = point_on_input(2.62, -0.18)
box("input_hopper_saddle", input_mount_point,
    (0.30, 2.14, 0.20), MAT_DARK, bevel=0.018)
for y in (-1.02, 1.02):
    box("input_hopper_receiver", (-1.80, y, 4.07),
        (0.42, 0.20, 0.28), MAT_DARK, bevel=0.018)
    box("input_hopper_clamp",
        (input_mount_point.x, y, input_mount_point.z + 0.12),
        (0.34, 0.20, 0.36), MAT_BLUE, bevel=0.018)

# Discharge conveyor and chute to the right.
out_angle = math.radians(11.0)
box("output_belt", (2.55, 0.0, 0.92), (2.75, 1.80, 0.13), MAT_RUBBER, rotation=(0, out_angle, 0), bevel=0.025)
for y in (-0.99, 0.99):
    box("output_side_rail", (2.55, y, 1.06), (2.84, 0.14, 0.38), MAT_BLUE,
        rotation=(0, out_angle, 0), bevel=0.04)
box("output_chute", (1.40, 0.0, 1.42), (0.70, 2.08, 0.75), MAT_DARK, bevel=0.055)
cylinder("output_roller", (3.86, 0.0, 1.17), 0.16, 1.88, MAT_STEEL,
         rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.015)

output_slat_pitch = 0.265
# The compact discharge belt needs a shorter pattern so neither terminal cleat
# can leave the side rails during the one-pitch animation cycle.
for index in range(9):
    local_x = -1.15 + index * output_slat_pitch
    slat_x = 2.55 + math.cos(out_angle) * local_x
    slat_z = 0.92 - math.sin(out_angle) * local_x + 0.095
    output_slat_parts.append(
        box("output_moving_cleat", (slat_x, 0.0, slat_z),
            (0.055, 1.68, 0.042), MAT_STEEL,
            rotation=(0, out_angle, 0), bevel=0.007)
    )

for local_x in (-0.25, 1.05):
    support_x = 2.55 + math.cos(out_angle) * local_x
    support_z = 0.92 - math.sin(out_angle) * local_x - 0.12
    for y in (-0.84, 0.84):
        leg_height = max(0.24, support_z - 0.15)
        box("output_support_leg", (support_x, y, 0.12 + leg_height * 0.5),
            (0.14, 0.14, leg_height), MAT_DARK, bevel=0.012)
        box("output_support_foot", (support_x, y, 0.055),
            (0.40, 0.36, 0.11), MAT_STEEL, bevel=0.016)
    box("output_support_top_saddle", (support_x, 0.0, support_z - 0.03),
        (0.18, 1.86, 0.15), MAT_DARK, bevel=0.010)
    box("output_support_ground_tie", (support_x, 0.0, 0.24),
        (0.18, 1.76, 0.14), MAT_DARK, bevel=0.010)

# Control cabinet, indicators, emergency stop and vents.
box("control_cabinet", (1.82, -1.43, 2.02), (0.82, 0.30, 1.16), MAT_YELLOW, bevel=0.055)
box("control_face", (1.82, -1.602, 2.06), (0.65, 0.055, 0.88), MAT_DARK, bevel=0.025)
cylinder("emergency_stop", (1.97, -1.655, 2.25), 0.115, 0.09, MAT_RED,
         rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.016)
for i, (x, z, mat) in enumerate(((1.68, 2.25, MAT_GREEN), (1.68, 2.02, MAT_WHITE), (1.97, 2.02, MAT_YELLOW))):
    cylinder(f"control_button_{i}", (x, -1.655, z), 0.055, 0.06, mat,
             rotation=(math.radians(90), 0, 0), vertices=12, bevel=0.010)
for z in (1.74, 1.82, 1.90):
    box("cabinet_vent", (1.82, -1.65, z), (0.46, 0.035, 0.027), MAT_SILVER, bevel=0.004)

# Guards, handrails and ladder.
for x in (-0.95, 0.0, 0.95):
    pipe("guard_post", (x, 1.30, 2.77), (x, 1.30, 3.62), 0.038, MAT_YELLOW)
pipe("guard_top", (-1.10, 1.30, 3.61), (1.10, 1.30, 3.61), 0.040, MAT_YELLOW)
pipe("guard_mid", (-1.10, 1.30, 3.22), (1.10, 1.30, 3.22), 0.032, MAT_YELLOW)
for z in (0.55, 0.92, 1.29, 1.66, 2.03, 2.40):
    pipe("ladder_rung", (1.49, 1.55, z), (1.49, 2.05, z), 0.030, MAT_YELLOW)
pipe("ladder_side", (1.49, 1.58, 0.25), (1.49, 1.58, 2.65), 0.045, MAT_YELLOW)
pipe("ladder_side", (1.49, 2.02, 0.25), (1.49, 2.02, 2.65), 0.045, MAT_YELLOW)

# Panel fasteners and readable industrial markings.
for x in (-1.18, -0.60, 0.0, 0.60, 1.18):
    for z in (1.79, 2.56):
        cylinder("panel_bolt", (x, -1.295, z), 0.037, 0.035, MAT_SILVER,
                 rotation=(math.radians(90), 0, 0), vertices=8, bevel=0.005)
add_text("brand_badge", "INDUSTRIAL", (0.0, -1.315, 2.33), 0.245, MAT_WHITE)
add_text("machine_badge", "SCRAP SHREDDER", (0.0, -1.32, 2.02), 0.185, MAT_YELLOW)

# Hazard stripes on the lower front apron.
for i in range(11):
    x = -1.20 + i * 0.24
    box("hazard_stripe", (x, -1.30, 1.53), (0.14, 0.045, 0.30), MAT_YELLOW if i % 2 == 0 else MAT_DARK,
        rotation=(0.0, math.radians(24), 0.0), bevel=0.008)

# Lightweight native output fragment used by the gameplay script. Keeping the
# debris model inside this resource avoids dependencies on uncertain map props.
scrap_chunk_parts.append(
    box("output_scrap_plate", (0.0, 0.0, 0.0), (0.34, 0.16, 0.075),
        MAT_STEEL, rotation=(math.radians(12), math.radians(-18), math.radians(21)),
        bevel=0.012)
)
scrap_chunk_parts.append(
    box("output_scrap_flange", (0.04, -0.01, 0.055), (0.20, 0.08, 0.16),
        MAT_DARK, rotation=(math.radians(-8), math.radians(28), math.radians(-16)),
        bevel=0.010)
)


def smart_uv(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.018)
    bpy.ops.object.mode_set(mode="OBJECT")
    obj.select_set(False)


# Merge explicitly instead of using Blender's background join operator. The
# animated rotors and moving cleat patterns remain separate lightweight models.
visual_materials = list(material_cache.values())

def merge_parts(asset_name, parts, origin=(0.0, 0.0, 0.0)):
    origin = Vector(origin)
    combined_vertices = []
    combined_faces = []
    combined_material_indices = []
    combined_smoothing = []
    used_material_indices = []
    for part in parts:
        if part.type != "MESH":
            continue
        vertex_offset = len(combined_vertices)
        combined_vertices.extend(part.matrix_world @ vertex.co - origin for vertex in part.data.vertices)
        source_slots = list(part.data.materials)
        for polygon in part.data.polygons:
            combined_faces.append(tuple(vertex_offset + index for index in polygon.vertices))
            source_material = source_slots[polygon.material_index]
            global_material_index = visual_materials.index(source_material)
            if global_material_index not in used_material_indices:
                used_material_indices.append(global_material_index)
            combined_material_indices.append(used_material_indices.index(global_material_index))
            combined_smoothing.append(polygon.use_smooth)

    combined_mesh = bpy.data.meshes.new(f"{asset_name}_mesh")
    combined_mesh.from_pydata(combined_vertices, [], combined_faces)
    combined_mesh.update()
    for material_index in used_material_indices:
        combined_mesh.materials.append(visual_materials[material_index])
    for polygon, material_index, use_smooth in zip(
        combined_mesh.polygons, combined_material_indices, combined_smoothing
    ):
        polygon.material_index = material_index
        polygon.use_smooth = use_smooth

    merged = bpy.data.objects.new(asset_name, combined_mesh)
    bpy.context.collection.objects.link(merged)
    merged.location = origin
    for part in parts:
        bpy.data.objects.remove(part, do_unlink=True)
    smart_uv(merged)
    tri = merged.modifiers.new("Game triangulation", "TRIANGULATE")
    bpy.context.view_layer.objects.active = merged
    merged.select_set(True)
    bpy.ops.object.modifier_apply(modifier=tri.name)
    merged.select_set(False)
    return merged


component_parts = (rotor_parts[0] + rotor_parts[1] + input_slat_parts +
                   output_slat_parts + scrap_chunk_parts)
component_ids = {part.as_pointer() for part in component_parts}
body_parts = [part for part in render_parts if part.as_pointer() not in component_ids]

model = merge_parts(ASSET_NAME, body_parts)
rotor_a_model = merge_parts(f"{ASSET_NAME}_rotor_a", rotor_parts[0], (-0.39, 0.0, 2.44))
rotor_b_model = merge_parts(f"{ASSET_NAME}_rotor_b", rotor_parts[1], (0.39, 0.0, 2.44))
input_slat_model = merge_parts(f"{ASSET_NAME}_belt_in", input_slat_parts)
output_slat_model = merge_parts(f"{ASSET_NAME}_belt_out", output_slat_parts)
scrap_chunk_model = merge_parts(f"{ASSET_NAME}_chunk", scrap_chunk_parts)
models = [model, rotor_a_model, rotor_b_model, input_slat_model, output_slat_model,
          scrap_chunk_model]
model_names = [source_model.name for source_model in models]

# Preserve an assembled, editable source GLB before adding GTA hierarchy objects.
bpy.ops.object.select_all(action="DESELECT")
for source_model in models:
    source_model.select_set(True)
bpy.context.view_layer.objects.active = model
bpy.ops.export_scene.gltf(
    filepath=str(SOURCE_OUT / f"{ASSET_NAME}.glb"),
    export_format="GLB",
    use_selection=True,
    export_apply=True,
    export_materials="EXPORT",
)


def make_decimated_mesh(source_obj, name, ratio):
    temp = source_obj.copy()
    temp.data = source_obj.data.copy()
    temp.name = name
    bpy.context.collection.objects.link(temp)
    bpy.context.view_layer.objects.active = temp
    temp.select_set(True)
    dec = temp.modifiers.new("LOD reduction", "DECIMATE")
    dec.ratio = ratio
    dec.use_collapse_triangulate = True
    bpy.ops.object.modifier_apply(modifier=dec.name)
    mesh = temp.data
    mesh.name = f"{name}_mesh"
    bpy.data.objects.remove(temp, do_unlink=True)
    return mesh


lod_meshes = {}
for source_model in models:
    lod_meshes[source_model.name] = (
        make_decimated_mesh(source_model, f"{source_model.name}_medium", 0.58),
        make_decimated_mesh(source_model, f"{source_model.name}_low", 0.27),
    )

medium_mesh, low_mesh = lod_meshes[ASSET_NAME]


# Neutral studio preview environment.
ground_mat = bpy.data.materials.new("preview_ground")
ground_mat.diffuse_color = (0.055, 0.060, 0.065, 1.0)
ground_mat.use_nodes = True
ground_mat.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.055, 0.060, 0.065, 1.0)
ground_mat.node_tree.nodes.get("Principled BSDF").inputs["Roughness"].default_value = 0.88
ground = box("preview_ground", (-1.0, 0, -0.06), (16, 11, 0.10), ground_mat, bevel=0.0, add=False)

bpy.ops.object.light_add(type="AREA", location=(-4.5, -5.5, 7.0))
key = bpy.context.object
key.data.energy = 2200
key.data.shape = "DISK"
key.data.size = 5.0
bpy.ops.object.light_add(type="AREA", location=(4.0, 2.5, 5.2))
fill = bpy.context.object
fill.data.energy = 1550
fill.data.size = 4.0
bpy.ops.object.light_add(type="AREA", location=(0.0, 4.0, 7.5))
rim = bpy.context.object
rim.data.energy = 1850
rim.data.size = 3.0
bpy.ops.object.light_add(type="SUN", location=(0, 0, 8))
sun = bpy.context.object
sun.data.energy = 2.0
sun.rotation_euler = (math.radians(28), math.radians(-22), math.radians(-32))
bpy.context.scene.world.color = (0.07, 0.08, 0.10)

def aim_light(light, target=(0.0, 0.0, 1.8)):
    light.rotation_euler = (Vector(target) - light.location).to_track_quat("-Z", "Y").to_euler()

for studio_light in (key, fill, rim):
    aim_light(studio_light)

bpy.ops.object.camera_add(location=(-10.8, -11.5, 6.4))
camera = bpy.context.object
bpy.context.scene.camera = camera


def point_camera(target=(0.0, 0.0, 1.8)):
    camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()


scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.device = "CPU"
scene.cycles.samples = 28
scene.cycles.use_denoising = True
scene.view_settings.look = "AgX - Medium High Contrast"
scene.view_settings.exposure = 0.75
scene.render.resolution_x = 900
scene.render.resolution_y = 700
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.color_depth = "8"
scene.render.resolution_percentage = 100
scene.render.filepath = str(PREVIEW_OUT / f"{ASSET_NAME}_hero.png")
point_camera((-1.25, 0.0, 2.15))
bpy.ops.render.render(write_still=True)

camera.location = (-0.2, -8.1, 8.6)
point_camera((0.0, 0.0, 2.0))
scene.render.filepath = str(PREVIEW_OUT / f"{ASSET_NAME}_cutters.png")
bpy.ops.render.render(write_still=True)

camera.location = (-10.4, -8.8, 2.75)
point_camera((-1.20, 0.0, 2.05))
scene.render.filepath = str(PREVIEW_OUT / f"{ASSET_NAME}_structure.png")
bpy.ops.render.render(write_still=True)

# Remove preview-only scene elements from exports and source blend.
for obj in (ground, key, fill, rim, camera):
    bpy.data.objects.remove(obj, do_unlink=True)
for obj in list(bpy.data.objects):
    if obj.type == "LIGHT":
        bpy.data.objects.remove(obj, do_unlink=True)

# Component meshes were positioned for the assembled preview. Their GTA props
# need a zeroed object transform so AttachEntityToEntity uses the intended
# origin (the two rotor meshes are already centered on their shaft axes).
for component_model in (rotor_a_model, rotor_b_model):
    component_model.location = (0.0, 0.0, 0.0)


def convert_model_to_drawable(source_model):
    source_name = source_model.name
    component_medium, component_low = lod_meshes[source_name]
    converted_materials = []
    for source_mat in list(source_model.data.materials):
        converter = MaterialConverter(source_model, source_mat)
        gta_mat = converter.auto_convert()
        diffuse = gta_mat.node_tree.nodes.get("DiffuseSampler")
        if diffuse is None or diffuse.image is None:
            raise RuntimeError(f"Sollumz did not create a diffuse sampler for {source_mat.name}")
        diffuse.texture_properties.embedded = True
        converted_materials.append(gta_mat)

    for lod_mesh in (component_medium, component_low):
        lod_mesh.materials.clear()
        for mat in converted_materials:
            lod_mesh.materials.append(mat)

    component_drawable = convert_obj_to_drawable(source_model)
    component_drawable.name = source_name
    source_model.name = f"{source_name}.model"
    source_model.sollumz_lods.set_lod_mesh(LODLevel.MEDIUM, component_medium)
    source_model.sollumz_lods.set_lod_mesh(LODLevel.LOW, component_low)
    component_drawable.drawable_properties.lod_dist_high = 110
    component_drawable.drawable_properties.lod_dist_med = 190
    component_drawable.drawable_properties.lod_dist_low = 280
    component_drawable.drawable_properties.lod_dist_vlow = 380
    return component_drawable, len(converted_materials)


drawables = []
material_counts = {}
for source_model in models:
    source_name = source_model.name
    component_drawable, material_count = convert_model_to_drawable(source_model)
    drawables.append(component_drawable)
    material_counts[source_name] = material_count

drawable = drawables[0]

# Low-cost embedded static collision assembled from a few large metal volumes.
collision_parts = []
def collision_box(name, location, dimensions, rotation=(0.0, 0.0, 0.0)):
    obj = box(name, location, dimensions, None, rotation=rotation, bevel=0.0, add=False)
    collision_parts.append(obj)
    return obj

# Open collision shell: items can travel up the feed belt and fall through the
# hopper into the animated cutter throat instead of landing on an invisible box.
collision_box("col_chamber_front", (0.0, -1.29, 2.05), (2.95, 0.18, 1.55))
collision_box("col_chamber_back", (0.0, 1.29, 2.05), (2.95, 0.18, 1.55))
collision_box("col_chamber_left", (-1.42, 0.0, 2.05), (0.18, 2.55, 1.55))
collision_box("col_chamber_right", (1.42, 0.0, 2.05), (0.18, 2.55, 1.55))
collision_box("col_chamber_floor", (0.0, 0.0, 1.33), (2.70, 2.42, 0.36))
for x in (-1.05, 1.05):
    for y in (-1.05, 1.05):
        collision_box("col_machine_pedestal", (x, y, 0.75),
                      (0.26, 0.26, 0.86))

hopper_slope = math.radians(17.0)
collision_box("col_hopper_front", (0.0, -1.29, 3.42), (3.48, 0.13, 1.48), rotation=(hopper_slope, 0, 0))
collision_box("col_hopper_back", (0.0, 1.29, 3.42), (3.48, 0.13, 1.48), rotation=(-hopper_slope, 0, 0))
collision_box("col_hopper_left", (-1.48, 0.0, 3.42), (0.13, 3.02, 1.48), rotation=(0, -hopper_slope, 0))
collision_box("col_hopper_right", (1.48, 0.0, 3.42), (0.13, 3.02, 1.48), rotation=(0, hopper_slope, 0))

# A dedicated walk surface sits at cleat height. The previous collision volume
# was centered on the rubber carcass, which let ped capsules settle visibly
# through the belt before contacting it.
collision_box("col_input_walk_surface", (input_center.x, 0.0, input_center.z + 0.10),
              (input_length - 0.20, 1.74, 0.10), rotation=(0, conv_angle, 0))
for y in (-1.02, 1.02):
    collision_box("col_input_rail", (input_center.x, y, input_center.z + 0.22),
                  (input_length + 0.12, 0.16, 0.46), rotation=(0, conv_angle, 0))
for y in (-0.88, 0.88):
    collision_box("col_input_foundation", ((rail_start + rail_end) * 0.5, y, 0.15),
                  (rail_end - rail_start, 0.24, 0.20))
for support_point in input_support_points:
    for y in (-0.88, 0.88):
        leg_bottom = 0.22
        leg_top = support_point.z - 0.03
        leg_height = max(0.22, leg_top - leg_bottom)
        collision_box("col_input_support", (support_point.x, y, leg_bottom + leg_height * 0.5),
                      (0.16, 0.16, leg_height))
    collision_box("col_input_top_saddle", (support_point.x, 0.0, support_point.z - 0.04),
                  (0.20, 2.00, 0.18))
collision_box("col_input_hopper_saddle", input_mount_point, (0.32, 2.16, 0.22))
collision_box("col_output_walk_surface", (2.55, 0.0, 1.01),
              (2.65, 1.70, 0.10), rotation=(0, out_angle, 0))
for local_x in (-0.25, 1.05):
    support_x = 2.55 + math.cos(out_angle) * local_x
    support_z = 0.92 - math.sin(out_angle) * local_x - 0.12
    for y in (-0.84, 0.84):
        leg_height = max(0.24, support_z - 0.15)
        collision_box("col_output_support", (support_x, y, 0.12 + leg_height * 0.5),
                      (0.16, 0.16, leg_height))
    collision_box("col_output_top_saddle", (support_x, 0.0, support_z - 0.03),
                  (0.20, 1.88, 0.17))
collision_box("col_drive", (0.0, 1.68, 1.80), (2.10, 0.78, 1.95))

# Ladder rails and rungs now contribute actual collision instead of being
# visual-only tubes.
collision_box("col_ladder_left", (1.49, 1.58, 1.45), (0.14, 0.14, 2.52))
collision_box("col_ladder_right", (1.49, 2.02, 1.45), (0.14, 0.14, 2.52))
for z in (0.55, 0.92, 1.29, 1.66, 2.03, 2.40):
    collision_box("col_ladder_rung", (1.49, 1.80, z), (0.14, 0.56, 0.10))

bpy.ops.object.select_all(action="DESELECT")
for obj in collision_parts:
    obj.select_set(True)
bpy.context.view_layer.objects.active = collision_parts[0]
bpy.ops.object.join()
collision_mesh = bpy.context.object
collision_mesh.name = f"{ASSET_NAME}_collision"
collision_mesh.data.materials.append(create_collision_material_from_index(59))
bvh = convert_obj_to_bvh(collision_mesh, apply_default_flags=False)
bvh.name = f"{ASSET_NAME}.col"
bvh.margin = 0.005
for flag_name in ("map_animal", "map_cover", "map_dynamic", "map_vehicle", "map_weapon"):
    setattr(bvh.composite_flags1, flag_name, True)
for flag_name in (
    "vehicle_not_bvh", "vehicle_bvh", "ped", "ragdoll", "animal", "animal_ragdoll",
    "object", "plant", "projectile", "explosion", "forklift_forks", "test_weapon",
    "test_camera", "test_ai", "test_script", "test_vehicle_wheel", "glass",
):
    setattr(bvh.composite_flags2, flag_name, True)
bvh.parent = drawable

# Persist editable source and export every CodeWalker XML drawable through Sollumz.
bpy.ops.wm.save_as_mainfile(filepath=str(SOURCE_OUT / f"{ASSET_NAME}.blend"))
for component_name, component_drawable in zip(model_names, drawables):
    ydr_xml = XML_OUT / f"{component_name}.ydr.xml"
    if not export_ydr(component_drawable, str(ydr_xml)):
        raise RuntimeError(f"Sollumz YDR export failed for {component_name}")

ytyp = ytyp_from_objects(drawables)
ytyp.name = ASSET_NAME
for archetype in ytyp.archetypes:
    archetype.lod_dist = 360
    archetype.hd_texture_dist = 170
    archetype.asset_name = archetype.name
ytyp.write_xml(str(XML_OUT / f"{ASSET_NAME}.ytyp.xml"))

component_triangles = {}
for component_name, source_model in zip(model_names, models):
    component_medium, component_low = lod_meshes[component_name]
    component_triangles[component_name] = {
        "high": len(source_model.data.polygons),
        "medium": len(component_medium.polygons),
        "low": len(component_low.polygons),
    }

stats = {
    "asset": ASSET_NAME,
    "dimensions_m": {"length": 10.70, "width": 4.08, "height": 4.72},
    "triangles": component_triangles,
    "collision_triangles": len(collision_mesh.data.polygons),
    "materials": len(visual_materials),
    "component_material_slots": sum(material_counts.values()),
    "embedded_textures": len(visual_materials),
    "animated_components": model_names[1:5],
    "output_piece_model": f"{ASSET_NAME}_chunk",
    "collision_material": "METAL_HOLLOW_MEDIUM",
    "sollumz": "2.4.2",
    "blender": bpy.app.version_string,
}
(OUT / "asset_stats.json").write_text(json.dumps(stats, indent=2), encoding="utf-8")
print("SHREDDER_BUILD_COMPLETE")
print(json.dumps(stats, indent=2))
