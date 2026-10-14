<script setup>
/**
 * 会计科目树。
 *
 * 这一页存在的意义是把系统最重要的设计决定讲清楚：资金账户与消费分类
 * 共处同一棵科目树，靠 subject_type 区分 —— 这正是「借 费用 ／ 贷 资产」
 * 能够配平的前提。所以页面上有一整段说明，而不是只给张表。
 */
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { formatMoney } from '@/utils/format'
import SubjectRow from '@/components/SubjectRow.vue'

const auth = useAuthStore()
const { fullTree, accounts } = storeToRefs(auth)
</script>

<template>
  <div class="page-head">
    <h2>分类与账户</h2>
  </div>

  <div class="panel">
    <div class="panel__body">
      <div class="alert alert--info" style="margin-bottom: 0">
        这里是你的记账分类总表：<b>花钱的类别</b>（餐饮、交通……）和
        <b>放钱的账户</b>（银行卡、支付宝……）都在这份列表里，
        另外还有记录收入的类别。
        <br /><br />
        这样做的好处是：每记一笔账，系统会同时记下"钱从哪个账户出去"和
        "算作哪一类消费"，两边的金额永远相等。
        所以就算某一笔记错了、漏记了，你也能一眼看出来是哪一笔，
        而不用靠手工核对余额。
        <br /><br />
        带「不可记账」标记的是分组，用来归类下面的子项；
        真正能记账的是最末尾的具体条目。
      </div>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head">
      <span>全部分类</span>
      <span class="muted" style="font-weight: 500; font-size: 12px">
        只有账户类条目的余额有实际意义
      </span>
    </div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>编号</th>
            <th>名称</th>
            <th>类别</th>
            <th>金额增加方向</th>
            <th>能否记账</th>
            <th class="num">余额</th>
          </tr>
        </thead>
        <tbody>
          <SubjectRow v-for="node in fullTree" :key="node.id" :node="node" />
          <tr v-if="!fullTree.length">
            <td colspan="6" class="empty">加载中…</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <div class="panel__head">资金账户一览</div>
    <div class="table-wrap">
      <table>
        <thead>
          <tr>
            <th>账户</th>
            <th>编号</th>
            <th>账户类型</th>
            <th class="num">余额</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in accounts" :key="a.id">
            <td>{{ a.name }}</td>
            <td class="mono">{{ a.code }}</td>
            <td><span class="tag">{{ a.accountKind }}</span></td>
            <td class="num">{{ formatMoney(a.balance) }}</td>
          </tr>
          <tr v-if="!accounts.length">
            <td colspan="4" class="empty">暂无资金账户</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
