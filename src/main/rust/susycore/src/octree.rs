const RESIZE_FRACTION: f32 = 2.0;
//octree mostly stolen from sable
pub struct Octree {
  pub data: Vec<i32>,
  pub log2_size: u32,
  size: i32,
  free_space_index_head: i32,
}

impl Octree {
  pub fn new(log2_size: u32) -> Self {
    let capacity = if log2_size > 0 {
      1 + 8 * ((8usize.pow(log2_size) - 1) / 7)
    } else {
      1
    };
    Self {
      data: vec![0; capacity],
      log2_size,
      size: 1,
      free_space_index_head: -1,
    }
  }

  #[inline]
  pub fn root_index(&self) -> usize {
    0
  }

  #[inline]
  pub fn children_index(&self, parent_index: usize) -> usize {
    self.data[parent_index] as usize
  }

  #[inline]
  pub fn is_empty(&self, index: usize) -> bool {
    self.data[index] == 0
  }

  #[inline]
  pub fn is_leaf(&self, index: usize) -> bool {
    self.data[index] < 0
  }

  #[inline]
  pub fn is_branch(&self, index: usize) -> bool {
    self.data[index] > 0
  }

  #[inline]
  pub fn leaf_value(&self, index: usize) -> u32 {
    (-self.data[index]) as u32
  }

  #[inline]
  pub fn child_index(&self, parent_index: usize, octant: usize) -> usize {
    self.children_index(parent_index) + octant
  }

  #[inline]
  pub fn branch_index(&self, parent_index: usize, octant: usize) -> usize {
    let start = self.data[parent_index] as usize;
    start + octant
  }

  pub fn initialize_branch(&mut self, parent_index: usize) {
    let branch_start = self.allocate_branch();
    self.data[parent_index] = branch_start;
    for i in 0..8 {
      self.data[(branch_start as usize) + i] = 0;
    }
  }

  #[inline]
  pub fn set_leaf(&mut self, index: usize, value: u32) {
    self.data[index] = -(value as i32);
  }

  #[inline]
  pub fn first_child_index(&self, parent_index: usize) -> Option<usize> {
    if self.is_branch(parent_index) {
      Some(self.data[parent_index] as usize)
    } else {
      None
    }
  }

  fn allocate_branch(&mut self) -> i32 {
    if self.free_space_index_head != -1 {
      let index = self.free_space_index_head - 1;
      self.free_space_index_head = self.data[index as usize];
      return index;
    }

    if self.size + 8 > self.data.len() as i32 {
      let new_size = (self.data.len() as f32 * RESIZE_FRACTION).ceil() as i32;
      if new_size > 268435455 {
        panic!("octree buffer is full");
      }
      self.data.resize(new_size as usize, 0);
    }

    let index = self.size;
    self.size += 8;
    index
  }

  pub fn query_aabb(&self, mins: [f32; 3], maxs: [f32; 3], mut f: impl FnMut(u32, u8, u8, u8)) {
    let size = 1 << self.log2_size;
    if maxs[0] < 0.0
      || mins[0] > size as f32
      || maxs[1] < 0.0
      || mins[1] > size as f32
      || maxs[2] < 0.0
      || mins[2] > size as f32
    {
      return;
    }
    let mut stack: Vec<(usize, u8, u8, u8, u8)> = Vec::with_capacity(128);
    stack.push((0, 0, 0, 0, self.log2_size as u8));
    while let Some((node, ox, oy, oz, level)) = stack.pop() {
      if self.data[node] == 0 {
        continue;
      }
      let node_size = 1 << level;
      let nx = ox as f32;
      let ny = oy as f32;
      let nz = oz as f32;
      let nx2 = nx + node_size as f32;
      let ny2 = ny + node_size as f32;
      let nz2 = nz + node_size as f32;
      if nx2 < mins[0]
        || nx > maxs[0]
        || ny2 < mins[1]
        || ny > maxs[1]
        || nz2 < mins[2]
        || nz > maxs[2]
      {
        continue;
      }
      if level == 0 {
        if self.data[node] < 0 {
          f((-self.data[node]) as u32, ox, oy, oz);
        }
      } else if self.data[node] > 0 {
        let child_level = level - 1;
        let base = self.data[node] as usize;
        for octant in 0..8u8 {
          let cx = ox + ((octant & 1) << child_level);
          let cy = oy + (((octant >> 1) & 1) << child_level);
          let cz = oz + (((octant >> 2) & 1) << child_level);
          stack.push((base + octant as usize, cx, cy, cz, child_level));
        }
      }
    }
  }

  pub fn set_block(&mut self, x: u8, y: u8, z: u8, handle: Option<u32>) {
    let bits = |val: u8, bit: u8| usize::from(val & bit != 0);
    let octant4 = bits(x, 8) | (bits(y, 8) << 1) | (bits(z, 8) << 2);
    let octant3 = bits(x, 4) | (bits(y, 4) << 1) | (bits(z, 4) << 2);
    let octant2 = bits(x, 2) | (bits(y, 2) << 1) | (bits(z, 2) << 2);
    let octant1 = bits(x, 1) | (bits(y, 1) << 1) | (bits(z, 1) << 2);

    let node = 0usize;
    if self.data[node] == 0 && handle.is_some() {
      self.initialize_branch(node);
    }
    let node = if self.data[node] > 0 {
      self.child_index(node, octant4)
    } else {
      return;
    };
    if self.data[node] == 0 && handle.is_some() {
      self.initialize_branch(node);
    }
    let node = if self.data[node] > 0 {
      self.child_index(node, octant3)
    } else {
      return;
    };
    if self.data[node] == 0 && handle.is_some() {
      self.initialize_branch(node);
    }
    let node = if self.data[node] > 0 {
      self.child_index(node, octant2)
    } else {
      return;
    };
    if self.data[node] == 0 && handle.is_some() {
      self.initialize_branch(node);
    }
    let node = if self.data[node] > 0 {
      self.child_index(node, octant1)
    } else {
      return;
    };

    match handle {
      Some(h) => self.data[node] = -(h as i32),
      None => self.data[node] = 0,
    }
  }

  pub fn delete_branch(&mut self, parent_index: usize) {
    let branch_start = self.data[parent_index as usize];
    if branch_start <= 0 {
      return;
    }
    self.data[parent_index as usize] = 0;
    self.data[branch_start as usize] = self.free_space_index_head;
    self.free_space_index_head = branch_start;
    for i in 1..8 {
      self.data[(branch_start as usize) + i] = 0;
    }
  }
}
