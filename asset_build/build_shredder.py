import json
import math
import os
import random
import sys
from pathlib import Path

import bpy
from mathutils import Vector


ASSET_NAME = "vrp_scrap_shredder"
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
material_cache = {}


def clamp(v):
    return max(0.0, min(1.0, v))


def make_texture(name, color, rust=0.0, grime=0.0, scratches=0.0, size=256):
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


MAT_BLUE = make_material("shredder_blue", (0.055, 0.13, 0.17), 0.72, 0.33, 0.045, 0.025, 0.30)
MAT_YELLOW = make_material("safety_yellow", (0.93, 0.51, 0.025), 0.45, 0.39, 0.030, 0.018, 0.20)
MAT_STEEL = make_material("cutter_steel", (0.17, 0.19, 0.20), 0.90, 0.22, 0.055, 0.060, 0.38)
MAT_DARK = make_material("machine_dark", (0.025, 0.032, 0.036), 0.76, 0.28, 0.020, 0.055, 0.12)
MAT_RUBBER = make_material("belt_rubber", (0.018, 0.021, 0.022), 0.05, 0.80, 0.0, 0.05, 0.08)
MAT_RED = make_material("emergency_red", (0.61, 0.025, 0.018), 0.35, 0.34, 0.015, 0.01, 0.05)
MAT_SILVER = make_material("brushed_metal", (0.43, 0.47, 0.49), 0.93, 0.20, 0.015, 0.02, 0.18)
MAT_WHITE = make_material("label_white", (0.80, 0.80, 0.72), 0.10, 0.48, 0.01, 0.01, 0.02)
MAT_GREEN = make_material("indicator_green", (0.03, 0.55, 0.12), 0.18, 0.24, 0.0, 0.0, 0.0)


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
    obj.data.extrude = extrude
    obj.data.bevel_depth = 0.0025
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

# Twin shafts, cutter discs and interlocking teeth.
for shaft_idx, x in enumerate((-0.39, 0.39)):
    cylinder("main_shaft", (x, 0.0, 2.72), 0.16, 2.62, MAT_SILVER, rotation=(math.radians(90), 0, 0), vertices=16)
    for disc_idx in range(11):
        y = -1.02 + disc_idx * 0.204
        phase = (disc_idx % 2) * math.radians(30) + shaft_idx * math.radians(30)
        cylinder("cutter_disc", (x, y, 2.72), 0.39, 0.13, MAT_STEEL, rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.014)
        for tooth_idx in range(6):
            angle = phase + tooth_idx * math.tau / 6.0
            tx = x + math.cos(angle) * 0.36
            tz = 2.72 + math.sin(angle) * 0.36
            box("cutter_tooth", (tx, y, tz), (0.24, 0.145, 0.13), MAT_SILVER,
                rotation=(0.0, -angle, 0.0), bevel=0.022)

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

# Input conveyor at left.
conv_angle = math.radians(-7.0)
box("input_belt", (-2.88, 0.0, 1.09), (3.45, 1.86, 0.13), MAT_RUBBER, rotation=(0, conv_angle, 0), bevel=0.025)
for y in (-1.02, 1.02):
    box("input_side_rail", (-2.90, y, 1.25), (3.60, 0.14, 0.42), MAT_BLUE,
        rotation=(0, conv_angle, 0), bevel=0.04)
for x, z in ((-4.52, 0.88), (-1.25, 1.29)):
    cylinder("conveyor_roller", (x, 0.0, z), 0.17, 1.94, MAT_SILVER,
             rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.015)
for x in (-4.25, -3.00, -1.65):
    for y in (-0.88, 0.88):
        pipe("conveyor_leg", (x, y, 0.24), (x + 0.16, y, 0.93), 0.065, MAT_DARK, vertices=8)

# Discharge conveyor and chute to the right.
out_angle = math.radians(11.0)
box("output_belt", (2.55, 0.0, 0.92), (2.75, 1.80, 0.13), MAT_RUBBER, rotation=(0, out_angle, 0), bevel=0.025)
for y in (-0.99, 0.99):
    box("output_side_rail", (2.55, y, 1.06), (2.84, 0.14, 0.38), MAT_BLUE,
        rotation=(0, out_angle, 0), bevel=0.04)
box("output_chute", (1.40, 0.0, 1.42), (0.70, 2.08, 0.75), MAT_DARK, bevel=0.055)
cylinder("output_roller", (3.86, 0.0, 1.17), 0.17, 1.92, MAT_SILVER,
         rotation=(math.radians(90), 0, 0), vertices=16, bevel=0.015)

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
add_text("brand_badge", "VRP INDUSTRIAL", (0.0, -1.315, 2.33), 0.24, MAT_WHITE)
add_text("machine_badge", "SCRAP SHREDDER", (0.0, -1.32, 2.02), 0.19, MAT_YELLOW)

# Hazard stripes on the lower front apron.
for i in range(11):
    x = -1.20 + i * 0.24
    box("hazard_stripe", (x, -1.30, 1.53), (0.14, 0.045, 0.30), MAT_YELLOW if i % 2 == 0 else MAT_DARK,
        rotation=(0.0, math.radians(24), 0.0), bevel=0.008)


def smart_uv(obj):
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(66), island_margin=0.018)
    bpy.ops.object.mode_set(mode="OBJECT")
    obj.select_set(False)


for part in render_parts:
    if part.type == "MESH":
        smart_uv(part)

bpy.ops.object.select_all(action="DESELECT")
for part in render_parts:
    part.select_set(True)
bpy.context.view_layer.objects.active = render_parts[0]
bpy.ops.object.join()
model = bpy.context.object
model.name = ASSET_NAME

# Blender can retain duplicate material slots while joining hundreds of parts.
# Collapse them now so the GTA drawable has a small, deterministic shader list.
old_slots = list(model.data.materials)
unique_materials = []
slot_remap = {}
for old_index, mat in enumerate(old_slots):
    try:
        new_index = unique_materials.index(mat)
    except ValueError:
        new_index = len(unique_materials)
        unique_materials.append(mat)
    slot_remap[old_index] = new_index
for polygon in model.data.polygons:
    polygon.material_index = slot_remap[polygon.material_index]
model.data.materials.clear()
for mat in unique_materials:
    model.data.materials.append(mat)

# Triangulate once and preserve a source GLB before adding GTA hierarchy objects.
tri = model.modifiers.new("Game triangulation", "TRIANGULATE")
bpy.context.view_layer.objects.active = model
bpy.ops.object.modifier_apply(modifier=tri.name)

bpy.ops.object.select_all(action="DESELECT")
model.select_set(True)
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


medium_mesh = make_decimated_mesh(model, f"{ASSET_NAME}_medium", 0.56)
low_mesh = make_decimated_mesh(model, f"{ASSET_NAME}_low", 0.24)


# Neutral studio preview environment.
ground_mat = bpy.data.materials.new("preview_ground")
ground_mat.diffuse_color = (0.055, 0.060, 0.065, 1.0)
ground_mat.use_nodes = True
ground_mat.node_tree.nodes.get("Principled BSDF").inputs["Base Color"].default_value = (0.055, 0.060, 0.065, 1.0)
ground_mat.node_tree.nodes.get("Principled BSDF").inputs["Roughness"].default_value = 0.88
ground = box("preview_ground", (0, 0, -0.06), (12, 10, 0.10), ground_mat, bevel=0.0, add=False)

bpy.ops.object.light_add(type="AREA", location=(-4.5, -5.5, 7.0))
key = bpy.context.object
key.data.energy = 1250
key.data.shape = "DISK"
key.data.size = 5.0
bpy.ops.object.light_add(type="AREA", location=(4.0, 2.5, 5.2))
fill = bpy.context.object
fill.data.energy = 900
fill.data.size = 4.0
bpy.ops.object.light_add(type="AREA", location=(0.0, 4.0, 7.5))
rim = bpy.context.object
rim.data.energy = 1050
rim.data.size = 3.0
bpy.ops.object.light_add(type="SUN", location=(0, 0, 8))
bpy.context.object.data.energy = 1.25
bpy.context.scene.world.color = (0.025, 0.032, 0.045)

bpy.ops.object.camera_add(location=(-8.7, -9.1, 6.3))
camera = bpy.context.object
bpy.context.scene.camera = camera


def point_camera(target=(0.0, 0.0, 1.8)):
    camera.rotation_euler = (Vector(target) - camera.location).to_track_quat("-Z", "Y").to_euler()


scene = bpy.context.scene
scene.render.engine = "CYCLES"
scene.cycles.device = "CPU"
scene.cycles.samples = 28
scene.cycles.use_denoising = True
scene.render.resolution_x = 900
scene.render.resolution_y = 700
scene.render.resolution_percentage = 100
scene.render.image_settings.file_format = "PNG"
scene.render.film_transparent = False
scene.render.image_settings.color_mode = "RGBA"
scene.render.image_settings.color_depth = "8"
scene.render.resolution_percentage = 100
scene.render.filepath = str(PREVIEW_OUT / f"{ASSET_NAME}_hero.png")
point_camera()
bpy.ops.render.render(write_still=True)

camera.location = (-0.2, -8.1, 8.6)
point_camera((0.0, 0.0, 2.0))
scene.render.filepath = str(PREVIEW_OUT / f"{ASSET_NAME}_cutters.png")
bpy.ops.render.render(write_still=True)

# Remove preview-only scene elements from exports and source blend.
for obj in (ground, key, fill, rim, camera):
    bpy.data.objects.remove(obj, do_unlink=True)
for obj in list(bpy.data.objects):
    if obj.type == "LIGHT":
        bpy.data.objects.remove(obj, do_unlink=True)

# Convert every visual material to a GTA shader and embed its diffuse texture.
converted_materials = []
for source_mat in list(model.data.materials):
    converter = MaterialConverter(model, source_mat)
    gta_mat = converter.auto_convert()
    diffuse = gta_mat.node_tree.nodes.get("DiffuseSampler")
    if diffuse is None or diffuse.image is None:
        raise RuntimeError(f"Sollumz did not create a diffuse sampler for {source_mat.name}")
    diffuse.texture_properties.embedded = True
    converted_materials.append(gta_mat)

for lod_mesh in (medium_mesh, low_mesh):
    lod_mesh.materials.clear()
    for mat in converted_materials:
        lod_mesh.materials.append(mat)

drawable = convert_obj_to_drawable(model)
drawable.name = ASSET_NAME
model.name = f"{ASSET_NAME}.model"
model.sz_lods.get_lod(LODLevel.MEDIUM).mesh = medium_mesh
model.sz_lods.get_lod(LODLevel.LOW).mesh = low_mesh
drawable.drawable_properties.lod_dist_high = 120
drawable.drawable_properties.lod_dist_med = 220
drawable.drawable_properties.lod_dist_low = 340
drawable.drawable_properties.lod_dist_vlow = 520

# Low-cost embedded static collision assembled from a few large metal volumes.
collision_parts = []
def collision_box(name, location, dimensions, rotation=(0.0, 0.0, 0.0)):
    obj = box(name, location, dimensions, None, rotation=rotation, bevel=0.0, add=False)
    collision_parts.append(obj)
    return obj

collision_box("col_chamber", (0.0, 0.0, 1.90), (2.95, 2.72, 2.80))
collision_box("col_input", (-2.90, 0.0, 0.72), (3.65, 2.20, 0.95), rotation=(0, conv_angle, 0))
collision_box("col_output", (2.60, 0.0, 0.67), (2.90, 2.12, 0.82), rotation=(0, out_angle, 0))
collision_box("col_drive", (0.0, 1.68, 1.80), (2.10, 0.78, 1.95))
collision_box("col_hopper", (0.0, 0.0, 3.45), (3.55, 3.12, 1.30))

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

# Persist editable source and export CodeWalker XML through Sollumz.
bpy.ops.wm.save_as_mainfile(filepath=str(SOURCE_OUT / f"{ASSET_NAME}.blend"))
ydr_xml = XML_OUT / f"{ASSET_NAME}.ydr.xml"
if not export_ydr(drawable, str(ydr_xml)):
    raise RuntimeError("Sollumz YDR export failed")

ytyp = ytyp_from_objects([drawable])
ytyp.name = ASSET_NAME
for archetype in ytyp.archetypes:
    archetype.lod_dist = 360
    archetype.hd_texture_dist = 170
    archetype.name = ASSET_NAME
    archetype.asset_name = ASSET_NAME
ytyp.write_xml(str(XML_OUT / f"{ASSET_NAME}.ytyp.xml"))

stats = {
    "asset": ASSET_NAME,
    "dimensions_m": {"length": 8.25, "width": 4.08, "height": 4.18},
    "triangles": {
        "high": len(model.data.polygons),
        "medium": len(medium_mesh.polygons),
        "low": len(low_mesh.polygons),
        "collision": len(collision_mesh.data.polygons),
    },
    "materials": len(converted_materials),
    "embedded_textures": len(converted_materials),
    "collision_material": "METAL_HOLLOW_MEDIUM",
    "sollumz": "2.4.2",
    "blender": bpy.app.version_string,
}
(OUT / "asset_stats.json").write_text(json.dumps(stats, indent=2), encoding="utf-8")
print("SHREDDER_BUILD_COMPLETE")
print(json.dumps(stats, indent=2))
