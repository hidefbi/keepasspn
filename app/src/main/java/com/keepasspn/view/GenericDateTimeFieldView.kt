package com.keepasspn.view

import com.keepasspn.database.element.DateInstant

interface GenericDateTimeFieldView: GenericFieldView {
    var activation: Boolean
    var dateTime: DateInstant
}