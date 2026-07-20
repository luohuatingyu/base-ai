<template>
  <div class="panel"><div class="section-head"><div><h2>权限菜单</h2><p>权限编码同时用于前端路由和后端接口校验。</p></div><el-button type="primary" @click="open()">新增权限</el-button></div>
    <el-table :data="rows"><el-table-column prop="name" label="名称"/><el-table-column prop="permission" label="权限编码"/><el-table-column prop="sortOrder" label="排序" width="90"/><el-table-column label="操作" width="100"><template #default="s"><el-button link type="primary" @click="open(s.row)">编辑</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="form.id?'编辑权限':'新增权限'" width="520px"><el-form label-width="90px"><el-form-item label="名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="权限编码"><el-input v-model="form.permission"/></el-form-item><el-form-item label="排序"><el-input-number v-model="form.sortOrder"/></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'; import { ElMessage } from 'element-plus'; import http from '../api/http'
const rows=ref([]),visible=ref(false); const form=reactive({id:null,name:'',permission:'',sortOrder:0,enabled:true})
async function load(){rows.value=(await http.get('/system/menus')).data}
/** 打开权限菜单编辑窗口。 */
function open(row){Object.assign(form,row?{...row}:{id:null,name:'',permission:'',sortOrder:0,enabled:true});visible.value=true}
/** 保存权限菜单。 */
async function save(){try{form.id?await http.put(`/system/menus/${form.id}`,form):await http.post('/system/menus',form);visible.value=false;await load();ElMessage.success('保存成功')}catch(e){ElMessage.error(e.response?.data?.message||'保存失败')}}
onMounted(load)
</script>
