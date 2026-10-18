<script setup>
/**
 * 支出分类管理。
 *
 * 这是「记一笔」之外的第二个入口，专门做分类维护：
 * 新建、改名、停用、删除。
 *
 * 三处设计约束：
 *
 * 1. **不显示"费用"这个根节点**。它是类型容器，不是分类；
 *    用户看到的所有条目都是他真正会选的类别。
 *
 * 2. **用分组表头而不是空格缩进表达层级**。早先在记账下拉框里用全角空格
 *    缩进，层级混合时看起来参差（原生 select 对空白处理不一致）。
 *    这里用「分组标题 + 子项卡片」的结构，层级靠视觉块区分，不靠空白。
 *
 * 3. **删除失败的提示如实转达后端原话**。后端在"已被账目引用"时返回
 *    「该科目已被 N 条分录使用，只能停用不能删除」—— 这句话本身就是最好的解释，
 *    前端不该改成含糊的"操作失败"。
 */
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const showForm = ref(false)
const busy = ref(false)
const newName = ref('')
const newParentId = ref('')

const editingId = ref(null)
const editingName = ref('')
const saving = ref(false)

/**
 * 归一化成两级结构：[{ group, items: [] }]。
 *
 * 支出树形如：费用(容器) → 餐饮(有子) → 早餐(叶子)
 *             费用(容器) → 医疗健康(叶子，无子)
 * 有子的作为分组表头，无子的（含二级叶子）作为可直接操作的条目。
 */
const groups = computed(() => {
  const tree = auth.expenseTree || []
  // 剥掉类型容器那一层
  const level2 = (tree.length === 1 && !tree[0].leaf && tree[0].children?.length)
    ? tree[0].children
    : tree

  return level2.map((node) => ({
    group: node,
    isGroup: !node.leaf,
    items: node.leaf ? [] : (node.children || [])
  }))
})

/** 可作为父级的分类：有子的二级节点。 */
const parentOptions = computed(() =>
  groups.value.filter((g) => g.isGroup).map((g) => ({ id: g.group.id, name: g.group.name }))
)

const totalCount = computed(() =>
  groups.value.reduce((n, g) => n + (g.isGroup ? g.items.length + 1 : 1), 0)
)

function openForm() {
  newName.value = ''
  newParentId.value = ''
  showForm.value = true
}

async function create() {
  const name = newName.value.trim()
  if (!name) {
    auth.notify('error', '请输入分类名称')
    return
  }
  busy.value = true
  try {
    await api.createSubject({
      name,
      subjectType: 'EXPENSE',
      parentId: newParentId.value ? Number(newParentId.value) : null
    })
    auth.notify('ok', `已新建「${name}」`)
    showForm.value = false
    await auth.loadSubjects()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    busy.value = false
  }
}

function startRename(node) {
  editingId.value = node.id
  editingName.value = node.name
}

function cancelRename() {
  editingId.value = null
  editingName.value = ''
}

async function commitRename(node) {
  const name = editingName.value.trim()
  if (!name) {
    auth.notify('error', '名称不能为空')
    return
  }
  if (name === node.name) {
    cancelRename()
    return
  }
  saving.value = true
  try {
    await api.renameSubject(node.id, name)
    auth.notify('ok', '已改名')
    cancelRename()
    await auth.loadSubjects()
  } catch (e) {
    auth.notify('error', e.message)
  } finally {
    saving.value = false
  }
}

async function toggle(node) {
  try {
    await api.setSubjectEnabled(node.id, !node.enabled)
    auth.notify('ok', node.enabled ? `已停用「${node.name}」` : `已启用「${node.name}」`)
    await auth.loadSubjects()
  } catch (e) {
    auth.notify('error', e.message)
  }
}

async function remove(node) {
  if (!window.confirm(`删除分类「${node.name}」？\n\n已被账目使用或含子分类的不能删除，需要先停用。`)) {
    return
  }
  try {
    await api.deleteSubject(node.id)
    auth.notify('ok', `已删除「${node.name}」`)
    await auth.loadSubjects()
  } catch (e) {
    // 后端会说「该科目已被 N 条分录使用，只能停用不能删除」，原样转达
    auth.notify('error', e.message)
  }
}

onMounted(() => auth.loadSubjects())
</script>

<template>
  <div class="page-head">
    <h2>支出分类</h2>
    <button v-if="!showForm" class="btn--ghost btn--sm" @click="openForm">+ 新建分类</button>
  </div>

  <!-- 新建 -->
  <div v-if="showForm" class="panel">
    <div class="panel__head">新建分类</div>
    <div class="panel__body">
      <div class="row">
        <div class="field mb-0">
          <label class="field__label" for="cat-name">名称</label>
          <input
            id="cat-name"
            v-model="newName"
            placeholder="如：宠物、订阅服务"
            @keyup.enter="create"
          />
        </div>
        <div class="field mb-0">
          <label class="field__label" for="cat-parent">归到哪一类（可不选）</label>
          <select id="cat-parent" v-model="newParentId">
            <option value="">作为一级分类</option>
            <option v-for="p in parentOptions" :key="p.id" :value="p.id">{{ p.name }}</option>
          </select>
        </div>
        <button :disabled="busy" @click="create">{{ busy ? '新建中…' : '新建' }}</button>
        <button class="btn--ghost" @click="showForm = false">取消</button>
      </div>
    </div>
  </div>

  <!-- 列表 -->
  <div class="panel">
    <div class="panel__head">
      <span>全部支出分类</span>
      <span class="faint" style="font-size: 12px">共 {{ totalCount }} 个</span>
    </div>
    <div class="panel__body" style="padding-top: 0">
      <div v-if="!totalCount" class="empty">还没有分类，点右上角「+ 新建分类」开始</div>

      <div v-for="g in groups" :key="g.group.id" class="cat-block">
        <!-- 分组表头（有子分类的一级分类） -->
        <div v-if="g.isGroup" class="cat-head">
          <div class="cat-head__name">
            {{ g.group.name }}
            <span v-if="!g.group.enabled" class="tag tag--reversal">已停用</span>
            <span class="faint" style="font-size: 11px">{{ g.items.length }} 个子分类</span>
          </div>
          <div class="cat-actions">
            <button class="btn--ghost btn--sm" @click="startRename(g.group)">改名</button>
            <button class="btn--ghost btn--sm" @click="toggle(g.group)">
              {{ g.group.enabled ? '停用' : '启用' }}
            </button>
            <button class="btn--ghost btn--sm" @click="remove(g.group)">删除</button>
          </div>
        </div>

        <!-- 分组表头自身的行内改名 -->
        <div v-if="g.isGroup && editingId === g.group.id" class="cat-edit">
          <input v-model="editingName" @keyup.enter="commitRename(g.group)" @keyup.esc="cancelRename" />
          <button class="btn--sm" :disabled="saving" @click="commitRename(g.group)">保存</button>
          <button class="btn--ghost btn--sm" @click="cancelRename">取消</button>
        </div>

        <!-- 子分类行；无子分类时这一组本身就是一个可直接记账的条目 -->
        <div
          v-for="node in (g.isGroup ? g.items : [g.group])"
          :key="node.id"
          class="cat-row"
          :class="{ 'cat-row--muted': !node.enabled }"
        >
          <div class="cat-row__name">
            <template v-if="editingId === node.id">
              <input
                v-model="editingName"
                class="cat-row__input"
                @keyup.enter="commitRename(node)"
                @keyup.esc="cancelRename"
              />
            </template>
            <template v-else>
              {{ node.name }}
              <span v-if="!node.enabled" class="tag tag--reversal">已停用</span>
            </template>
          </div>

          <div class="cat-actions">
            <template v-if="editingId === node.id">
              <button class="btn--sm" :disabled="saving" @click="commitRename(node)">保存</button>
              <button class="btn--ghost btn--sm" @click="cancelRename">取消</button>
            </template>
            <template v-else>
              <button class="btn--ghost btn--sm" @click="startRename(node)">改名</button>
              <button class="btn--ghost btn--sm" @click="toggle(node)">
                {{ node.enabled ? '停用' : '启用' }}
              </button>
              <button class="btn--ghost btn--sm" @click="remove(node)">删除</button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 分组之间留出间距，层级靠"块"而不是空白表达 */
.cat-block { padding: var(--sp-3) 0; }
.cat-block + .cat-block { border-top: 1px solid var(--border); }

.cat-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  border-radius: var(--radius-sm);
  background: var(--surface-2);
}
.cat-head__name {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  font-weight: 700;
}

/* 子项：左侧色条表示从属关系，不用空格缩进 */
.cat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  margin-top: 4px;
  border-left: 3px solid var(--border-strong);
  border-radius: 0 var(--radius-xs) var(--radius-xs) 0;
}
.cat-row:hover { background: var(--surface-2); }
.cat-row--muted { opacity: 0.55; }

.cat-row__name {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
}
.cat-row__input { max-width: 220px; }

.cat-edit {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding: var(--sp-2) var(--sp-3);
}

.cat-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  flex-shrink: 0;
}
</style>
