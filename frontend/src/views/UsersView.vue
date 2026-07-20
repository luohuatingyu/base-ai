<template>
  <div class="panel">
    <div class="section-head"><div><h2>用户管理</h2><p>账号数据仅存储在 MySQL 系统库。</p></div><el-button type="primary" @click="open()">新增用户</el-button></div>
    <el-table :data="rows"><el-table-column prop="username" label="账号"/><el-table-column prop="displayName" label="名称"/><el-table-column label="状态"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{ s.row.enabled?'启用':'停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="s"><el-button link type="primary" @click="open(s.row)">编辑</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="form.id?'编辑用户':'新增用户'" width="520px"><el-form label-width="90px"><el-form-item label="账号"><el-input v-model="form.username" :disabled="Boolean(form.id)"/></el-form-item><el-form-item label="显示名称"><el-input v-model="form.displayName"/></el-form-item><el-form-item label="密码"><el-input v-model="form.password" type="password" show-password/></el-form-item><el-form-item label="角色"><el-select v-model="form.roleIds" multiple><el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id"/></el-select></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
const rows=ref([]), roles=ref([]), visible=ref(false)
const form=reactive({id:null,username:'',displayName:'',password:'',enabled:true,roleIds:[]})
async function load(){ [rows.value,roles.value]=await Promise.all([http.get('/system/users').then(r=>r.data),http.get('/system/roles').then(r=>r.data)]) }
/** 打开用户编辑窗口。 */
function open(row){ Object.assign(form,row?{...row,password:''}:{id:null,username:'',displayName:'',password:'',enabled:true,roleIds:[]}); visible.value=true }
/** 保存用户并刷新列表。 */
async function save(){ try{ form.id?await http.put(`/system/users/${form.id}`,form):await http.post('/system/users',form); visible.value=false; await load(); ElMessage.success('保存成功') }catch(e){ElMessage.error(e.response?.data?.message||'保存失败')} }
onMounted(load)
</script>
