package ru.radiationx.anilibria.screen.search

import kotlinx.coroutines.flow.MutableStateFlow
import ru.radiationx.anilibria.screen.LifecycleViewModel

abstract class BaseSearchValuesViewModel(
    argExtra: SearchValuesExtra,
) : LifecycleViewModel() {

    val progressState = MutableStateFlow(false)
    val valuesData = MutableStateFlow<List<String>>(emptyList())
    val checkedIndicesData = MutableStateFlow<List<Pair<Int, Boolean>>>(emptyList())
    val selectedIndex = MutableStateFlow<Int?>(null)

    protected val currentValues = mutableListOf<String>()
    protected val checkedValues = mutableSetOf<String>()

    init {
        checkedValues.addAll(argExtra.values)
        updateChecked()
        updateSelected()
    }

    /** «Готово»: закрыть панель (значения уже применены через [emitValues]). */
    abstract fun applyValues()

    /** Отдать отмеченные значения в форму: сетка за панелью обновляется сразу. */
    protected abstract fun emitValues()

    fun resetSelected() {
        checkedValues.clear()
        updateChecked()
        emitValues()
    }

    fun setSelected(index: Int, selected: Boolean) {
        val value = currentValues[index]
        if (selected) {
            checkedValues.add(value)
        } else {
            checkedValues.remove(value)
        }
        updateChecked()
        emitValues()
    }

    protected fun updateSelected() {
        if (currentValues.isEmpty() || checkedValues.isEmpty()) {
            return
        }
        val firstCheckedValue = currentValues.firstOrNull { checkedValues.contains(it) }
        firstCheckedValue?.also {
            selectedIndex.value = currentValues.indexOf(it)
        }
    }

    protected fun updateChecked() {
        checkedIndicesData.value =
            currentValues.mapIndexed { index, item -> Pair(index, checkedValues.contains(item)) }
    }
}