use crate::block_collisions::{BlockColliderInfoHandle, MinecraftBlockColliderInfo};
use crate::body::BLOCK_DENSITY;
use crate::terrain::CHUNK_VOLUME;
use rapier3d::math::Vec3;
use rapier3d::parry::bounding_volume::Aabb;

pub(crate) fn collider_handle_for_box(min: [f32; 3], max: [f32; 3]) -> BlockColliderInfoHandle {
  MinecraftBlockColliderInfo::new(
    0.5,
    BLOCK_DENSITY,
    0.0,
    vec![Aabb::new(Vec3::from_slice(&min), Vec3::from_slice(&max))],
  )
  .handle()
}

pub(crate) fn full_chunklet_data(
  handle: BlockColliderInfoHandle,
) -> [BlockColliderInfoHandle; CHUNK_VOLUME] {
  [handle; CHUNK_VOLUME]
}
