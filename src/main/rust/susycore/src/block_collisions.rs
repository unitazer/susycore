use std::collections::HashMap;

use jni::EnvUnowned;
use jni::objects::JClass;
use jni::sys::{jdouble, jdoubleArray, jint, jobjectArray};
use ordered_float::OrderedFloat;
use rapier3d::math::{Vec3, Vector3};
use rapier3d::parry::bounding_volume::Aabb;
use rapier3d::prelude::SharedShape;

pub struct ShapeCache(pub HashMap<(OrderedFloat<f32>, OrderedFloat<f32>, OrderedFloat<f32>), SharedShape>);
impl ShapeCache {
  fn from_aabb(&mut self, aabb: Aabb) -> SharedShape {
      let half = aabb.translated(-aabb.center()).half_extents();
      let key = (OrderedFloat(half.x), OrderedFloat(half.y), OrderedFloat(half.z));
      if let Some(shape) = self.0.get(&key) {
          return shape.clone();
      }
      let shape = SharedShape::cuboid(half.x, half.y, half.z);
      self.0.insert(key, shape.clone());
      shape
}
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_addChunk(
  mut env: EnvUnowned,
  _class: JClass,
  dim: jint,
) {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_initialize(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  gravity: jdouble,
  drag: jdouble,
) {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addColliderInfo(
  env: EnvUnowned,
  class: JClass,
  friction: jdouble,
  volume: jdouble,
  restitution: jdouble,
  aabbs: jdoubleArray,
) -> jint {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addChunk(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  x: jint,
  z: jint,
  data: jobjectArray,
) {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeChunk(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  x: jint,
  z: jint,
) {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_partialSubchunkUpdate(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  chunk_x: jint,
  chunk_z: jint,
  x: jint,
  y: jint,
  z: jint,
  new_data: jint,
) {
  todo!()
}
