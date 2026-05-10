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
    // worst-case capacity for a fully-dense octree at this depth
    let capacity = ((8usize.pow(log2_size) - 1) / 7).max(1024);
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
