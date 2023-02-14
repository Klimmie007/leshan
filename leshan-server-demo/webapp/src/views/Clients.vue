<!-----------------------------------------------------------------------------
 * Copyright (c) 2021 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
  ----------------------------------------------------------------------------->
<template>
  <div>
    <v-data-table
      dense
      v-if="!loading"
      :headers="headers"
      :items="registrations"
      item-key="endpoint"
      :items-per-page="10"
      class="elevation-0 fill-height ma-3"
      @click:row="openLink"
      :search="search"
      sort-by="registrationDate"
      sort-desc
    >
      <template v-slot:top>
        <v-alert type="success" v-model="alertSuccessVisible" dismissible>{{ alertSuccessMessage }}</v-alert>
        <v-alert type="error" v-model="alertFailureVisible" dismissible>{{ alertFailureMessage }}</v-alert>
        <v-toolbar flat>
          <v-toolbar-title v-if="$vuetify.breakpoint.smAndUp"
            >Registered Clients</v-toolbar-title
          >
          <v-divider
            v-if="$vuetify.breakpoint.smAndUp"
            class="mx-4"
            inset
            vertical
          ></v-divider>
          <v-text-field
            v-model="search"
            :append-icon="$icons.mdiMagnify"
            label="Search"
            single-line
            hide-details
            class="pa-2"
            clearable
          ></v-text-field>
          <v-divider
            v-if="$vuetify.breakpoint.smAndUp"
            class="mx-4"
            inset
            vertical
          ></v-divider>
          <v-btn
          @click="apiGetCall"
          >Save observations</v-btn>
          <v-divider
            v-if="$vuetify.breakpoint.smAndUp"
            class="mx-4"
            inset
            vertical
          ></v-divider>
          <input type="file" id="file" ref="file" v-on:change="onFileChange" hidden/>
          <v-btn
          @click="fileUpload"
          >Load observations</v-btn>
        </v-toolbar>
      </template>
      <!-- custom display for date column -->
      <template v-slot:item.registrationDate="{ item }">
        {{ new Date(item.registrationDate) | moment("MMM D, h:mm:ss A") }}
      </template>
      <template v-slot:item.lastUpdate="{ item }">
        {{ new Date(item.lastUpdate) | moment("MMM D, h:mm:ss A") }}
      </template>
      <template v-slot:item.infos="{ item }">
        <client-info :registration="item" tooltipleft />
      </template>
    </v-data-table>
  </div>
</template>

<script>
import ClientInfo from "../components/ClientInfo.vue";
import 'bootstrap/dist/css/bootstrap.min.css'
import 'jquery/src/jquery.js'
import 'bootstrap/dist/js/bootstrap.min.js'
import {saveAs} from 'file-saver'
import {groupBy} from 'lodash'

export default {
  components: { ClientInfo },
  useSSE: true,
  name: "Clients",
  data: () => ({
    loading: true,
    registrations: [],
    headers: [
      { text: "Client Endpoint", value: "endpoint" },
      { text: "Registration ID", value: "registrationId" },
      { text: "Registration Date", value: "registrationDate" },
      { text: "Last Update", value: "lastUpdate" },
      { text: "", value: "infos", sortable: false, align: "end" },
    ],
    search: "",
    alertSuccessMessage: "UwU",
    alertFailureMessage: "UwU",
    alertSuccessVisible: false,
    alertFailureVisible: false,
    file: null,
  }),
  methods: {
    showAlert(type, message) {
      if(type == "success")
      {
        this.alertSuccessMessage = message
        this.alertSuccessVisible = true
        this.alertFailureVisible = false
      }
      else
      {
        this.alertFailureMessage = message
        this.alertFailureVisible = true
        this.alertSuccessVisible = false
      }
    },
    onFileChange() {
      this.file = this.$refs.file.files[0]
      this.apiPostCall()
    },
    fileUpload() {
      document.getElementById("file").click()
    },
    apiPostCall() {
      if(this.file == null)
      {
        this.showAlert('error', "no file has been uploaded")
        return;
      }
      if(this.file['type'] != 'text/plain')
      {
        this.showAlert('error', 'uploaded a file that is not in plain text')
        return;
      }
      var reader = new FileReader()
      reader.addEventListener("loadend", (e) => {
        console.log(JSON.parse(e.target.result))
        this.axios
        .post("/api/observations", e.target.result)
        .then((response) => {

          this.showAlert("success", response.data)
        })
      })
      reader.readAsText(this.file)
      
    },
    apiGetCall(){
      this.axios
        .get("/api/observations")
        .then((response) => { 
          if(response.data[0] == undefined)
            {
              this.showAlert("error", "No observations exist")
              return;
            }
          const grouped = groupBy(response.data, (data) => {return data.ep})
          var unwrapped = []
          Object.keys(grouped).forEach(element => {
            var collect = []
            grouped[element].forEach(nestedElement => {
              collect = collect.concat(nestedElement.paths)
            })
            unwrapped.push({ep: element, paths: collect})
          })
          var blob = new Blob([JSON.stringify(unwrapped)], {type: "text/plain;charset=utf-8"})
          var reader = new FileReader()
          reader.addEventListener("loadend", (e) => {console.log(JSON.parse(e.target.result))})
          reader.readAsText(blob)
          saveAs(blob, "observations.txt")
          
          this.showAlert("success", "file has been created")
        })
    },
    openLink(reg) {
      this.$router.push(`/clients/${reg.endpoint}/3`);
    },
  },
  mounted() {
    // listen events to update registration.
    this.sse = this.$sse
      .create({ url: "api/event" })
      .on("REGISTRATION", (reg) => {
        this.registrations = this.registrations
          .filter((r) => reg.endpoint !== r.endpoint)
          .concat(reg);
      })
      .on("UPDATED", (msg) => {
        let reg = msg.registration;
        this.registrations = this.registrations
          .filter((r) => reg.registrationId !== r.registrationId)
          .concat(reg);
      })
      .on("DEREGISTRATION", (reg) => {
        this.registrations = this.registrations.filter(
          (r) => reg.registrationId !== r.registrationId
        );
      })
      .on("SLEEPING", (reg) => {
        for (var i = 0; i < this.registrations.length; i++) {
          if (this.registrations[i].endpoint === reg.ep) {
            this.registrations[i].sleeping = true;
          }
        }
      })
      .on("error", (err) => {
        console.error("sse unexpected error", err);
      });
    this.sse.connect().catch((err) => {
      console.error("Failed to connect to server", err);
    });

    // get all registrations
    this.axios
      .get("api/clients")
      .then(
        (response) => (
          (this.loading = false), (this.registrations = response.data)
        )
      );
  },
  beforeDestroy() {
    // close eventsource on destroy
    this.sse.disconnect();
  },
};
</script>
