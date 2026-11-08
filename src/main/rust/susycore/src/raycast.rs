use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use rapier3d::math::Vec3;
use rapier3d::parry::query::Ray;
use rapier3d::prelude::{ColliderHandle, QueryFilter};

use crate::scene::Scene;
use crate::{EntityId, Real};
const NO_HIT: jlong = -1;

impl Scene {
  pub fn cast_ray(
    &self,
    origin: Vec3,
    dir: Vec3,
    max_toi: Real,
    solid: bool,
  ) -> Option<(ColliderHandle, RayHit)> {
    let pipeline = self.world.broad_phase.as_query_pipeline(
      self.world.narrow_phase.query_dispatcher(),
      &self.world.bodies,
      &self.world.colliders,
      QueryFilter::default(),
    );
    let (handle, hit) = pipeline.cast_ray_and_get_normal(&Ray::new(origin, dir), max_toi, solid)?;
    let owner = self.collider_owners.get(&handle).copied();
    Some((
      handle,
      RayHit {
        toi: hit.time_of_impact,
        normal: hit.normal,
        owner,
      },
    ))
  }
}
pub struct RayHit {
  pub toi: Real,
  pub normal: Vec3,
  pub owner: Option<EntityId>,
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_castRay(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  ox: jdouble,
  oy: jdouble,
  oz: jdouble,
  dx: jdouble,
  dy: jdouble,
  dz: jdouble,
  max_toi: jdouble,
  solid: jboolean,
  out_arr: JDoubleArray,
) -> jlong {
  debug_assert!(world_id >= 0);
  let hit = Scene::with_scene(world_id as usize, |scene| {
    scene.cast_ray(
      Vec3::new(ox as Real, oy as Real, oz as Real),
      Vec3::new(dx as Real, dy as Real, dz as Real),
      max_toi as Real,
      solid,
    )
  })
  .flatten();
  let mut out = [0.0; 5];
  let handle = match hit {
    Some((handle, hit)) => {
      out[0] = hit.toi as f64;
      out[1] = hit.normal.x as f64;
      out[2] = hit.normal.y as f64;
      out[3] = hit.normal.z as f64;
      out[4] = hit.owner.map(|e| e.0 as f64).unwrap_or(-1.0);
      crate::jlong_handle(handle)
    }
    None => NO_HIT,
  };
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      out_arr.set_region(env, 0, &out)?;
      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  handle
}
