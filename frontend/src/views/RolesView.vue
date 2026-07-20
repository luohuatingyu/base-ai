<template>
  <div class="panel"><div class="section-head"><div><h2>角色管理</h2><p>角色通过权限菜单获得接口访问能力。</p></div><el-button type="primary" @click="open()">新增角色</el-button></div>
    <el-table :data="rows"><el-table-column prop="code" label="编码"/><el-table-column prop="name" label="名称"/><el-table-column label="状态"><template #default="s">{{s.row.enabled?'启用':'停用'}}</template></el-table-column><el-table-column label="操作" width="100"><template #default="s"><el-button link type="primary" @click="open(s.row)">编辑</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="form.id?'编辑角色':'新增角色'" width="560px"><el-form label-width="90px"><el-form-item label="编码"><el-input v-model="form.code" :disabled="Boolean(form.id)"/></el-form-item><el-form-item label="名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="权限"><el-select v-model="form.menuIds" multiple><el-option v-for="menu in menus" :key="menu.id" :label="`${menu.name} (${menu.permission})`" :value="menu.id"/></el-select></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'; import { ElMessage } from 'element-plus'; import http from '../api/http'
const rows=ref([]),menus=ref([]),visible=ref(false); const form=reactive({id:null,code:'',name:'',enabled:true,menuIds:[]})
async function load(){[rows.value,menus.value]=await Promise.all([http.get('/system/roles').then(r=>r.data),http.get('/system/menus').then(r=>r.data)])}
/** 打开角色编辑窗口。 */
function open(row){Object.assign(form,row?{...row}:{id:null,code:'',name:'',enabled:true,menuIds:[]});visible.value=true}
/** 保存角色权限。 */
async function save(){try{form.id?await http.put(`/system/roles/${form.id}`,form):await http.post('/system/roles',form);visible.value=false;await load();ElMessage.success('保存成功')}catch(e){ElMessage.error(e.response?.data?.message||'保存失败')}}
onMounted(load)
</script>
